/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.Behavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.cache.CachedChunk;
import baritone.cache.ChunkPacker;
import baritone.cache.WorldProvider;
import baritone.cache.WorldScanner;
import baritone.pathing.goals.Goal;
import baritone.pathing.goals.GoalComposite;
import baritone.pathing.goals.GoalTwoBlocks;
import baritone.pathing.path.IPath;
import baritone.utils.BlockStateInterface;
import baritone.utils.Helper;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineBehavior extends Behavior implements Helper {

    public static final MineBehavior INSTANCE = new MineBehavior();

    private MineBehavior() {
    }

    private List<Block> mining;
    private List<BlockPos> locationsCache;

    @Override
    public void onTick(TickEvent event) {
        if (mining == null) {
            return;
        }
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.get();
        if (mineGoalUpdateInterval != 0) {
            if (event.getCount() % mineGoalUpdateInterval == 0) {
                updateGoal();
            }
        }
        if (!Baritone.settings().cancelOnGoalInvalidation.get()) {
            return;
        }
        Optional<IPath> path = PathingBehavior.INSTANCE.getPath();
        if (!path.isPresent()) {
            return;
        }
        Goal currentGoal = PathingBehavior.INSTANCE.getGoal();
        if (currentGoal == null) {
            return;
        }
        Goal intended = path.get().getGoal();
        BlockPos end = path.get().getDest();
        if (intended.isInGoal(end) && !currentGoal.isInGoal(end)) {
            // this path used to end in the goal
            // but the goal has changed, so there's no reason to continue...
            PathingBehavior.INSTANCE.cancel();
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        updateGoal();
    }

    private void updateGoal() {
        if (mining == null) {
            return;
        }
        if (!locationsCache.isEmpty()) {
            locationsCache = prune(new ArrayList<>(locationsCache), mining, 64);
            PathingBehavior.INSTANCE.setGoal(new GoalComposite(locationsCache.stream().map(GoalTwoBlocks::new).toArray(Goal[]::new)));
            PathingBehavior.INSTANCE.path();
        }
        List<BlockPos> locs = scanFor(mining, 64);
        if (locs.isEmpty()) {
            logDebug("No locations for " + mining + " known, cancelling");
            cancel();
            return;
        }
        locationsCache = locs;
        PathingBehavior.INSTANCE.setGoal(new GoalComposite(locs.stream().map(GoalTwoBlocks::new).toArray(Goal[]::new)));
        PathingBehavior.INSTANCE.path();
    }

    public static List<BlockPos> scanFor(List<Block> mining, int max) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> uninteresting = new ArrayList<>();
        //long b = System.currentTimeMillis();
        for (Block m : mining) {
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(m)) {
                locs.addAll(WorldProvider.INSTANCE.getCurrentWorld().cache.getLocationsOf(ChunkPacker.blockToString(m), 1, 1));
            } else {
                uninteresting.add(m);
            }
        }
        //System.out.println("Scan of cached chunks took " + (System.currentTimeMillis() - b) + "ms");
        if (!uninteresting.isEmpty()) {
            //long before = System.currentTimeMillis();
            locs.addAll(WorldScanner.INSTANCE.scanLoadedChunks(uninteresting, max));
            //System.out.println("Scan of loaded chunks took " + (System.currentTimeMillis() - before) + "ms");
        }
        return prune(locs, mining, max);
    }

    public static List<BlockPos> prune(List<BlockPos> locs, List<Block> mining, int max) {
        BlockPos playerFeet = MineBehavior.INSTANCE.playerFeet();
        locs.sort(Comparator.comparingDouble(playerFeet::distanceSq));

        // remove any that are within loaded chunks that aren't actually what we want
        locs.removeAll(locs.stream()
                .filter(pos -> !(MineBehavior.INSTANCE.world().getChunk(pos) instanceof EmptyChunk))
                .filter(pos -> !mining.contains(BlockStateInterface.get(pos).getBlock()))
                .collect(Collectors.toList()));
        if (locs.size() > max) {
            locs = locs.subList(0, max);
        }
        return locs;
    }

    public void mine(String... blocks) {
        this.mining = blocks == null || blocks.length == 0 ? null : Arrays.stream(blocks).map(ChunkPacker::stringToBlock).collect(Collectors.toList());
        this.locationsCache = new ArrayList<>();
        updateGoal();
    }

    public void mine(Block... blocks) {
        this.mining = blocks == null || blocks.length == 0 ? null : Arrays.asList(blocks);
        this.locationsCache = new ArrayList<>();
        updateGoal();
    }

    public void cancel() {
        mine((String[]) null);
        PathingBehavior.INSTANCE.cancel();
    }
}
