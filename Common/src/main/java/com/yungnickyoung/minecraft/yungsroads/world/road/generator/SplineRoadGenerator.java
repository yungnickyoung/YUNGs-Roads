package com.yungnickyoung.minecraft.yungsroads.world.road.generator;

import com.yungnickyoung.minecraft.yungsroads.YungsRoadsCommon;
import com.yungnickyoung.minecraft.yungsroads.world.config.RoadFeatureConfiguration;
import com.yungnickyoung.minecraft.yungsroads.world.config.RoadTypeConfig;
import com.yungnickyoung.minecraft.yungsroads.world.road.Road;
import com.yungnickyoung.minecraft.yungsroads.world.road.segment.DefaultRoadSegment;
import com.yungnickyoung.minecraft.yungsroads.world.road.segment.SplineRoadSegment;
import com.yungnickyoung.minecraft.yungsroads.world.road.decoration.ConfiguredRoadDecoration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class SplineRoadGenerator extends AbstractRoadGenerator {
    private final ServerLevel serverLevel;
    private final ThreadLocal<WorldgenRandom> random = ThreadLocal.withInitial(() -> new WorldgenRandom(new LegacyRandomSource(0)));
//    private final List<AbstractRoadDecoration> decorations;

    public SplineRoadGenerator(ServerLevel serverLevel) {
        super();
        this.serverLevel = serverLevel;
//        this.decorations = new ArrayList<>();
//        this.decorations.add(new BushRoadDecoration(.3f));
//        this.decorations.add(new StoneLampPostRoadDecoration(.3f));
//        this.decorations.add(new SmallWoodBenchRoadDecoration(.3f));
//        this.decorations.add(new FeatureRoadDecoration(.5f, Holder.direct(ConfiguredFeatureModule.FLOWER_DEFAULT_DECORATION_PLACED)));
//        this.decorations.add(new FeatureRoadDecoration(.5f, Holder.direct(ConfiguredFeatureModule.SUNFLOWER_DECORATION_PLACED)));
    }

    @Override
    public Optional<Road> generateRoad(ChunkPos pos1, ChunkPos pos2) {
        // Make sure our starting position is always the one with lesser x value
        BlockPos blockPos1 = pos1.x <= pos2.x ? pos1.getWorldPosition() : pos2.getWorldPosition();
        BlockPos blockPos2 = pos1.x <= pos2.x ? pos2.getWorldPosition() : pos1.getWorldPosition();

        int xDist = blockPos2.getX() - blockPos1.getX();
        int zDist = blockPos2.getZ() - blockPos1.getZ();

        random.get().setLargeFeatureWithSalt(serverLevel.getSeed(), pos1.x, 0, pos1.z);

        // Construct road & road segments
        Road road = new Road(blockPos1, blockPos2);
        int numSegments = 6;
        for (int i = 0; i < numSegments; i++) {
            BlockPos.MutableBlockPos startPos = blockPos1.offset(xDist * i / numSegments, 0, zDist * i / numSegments).mutable();
            BlockPos.MutableBlockPos endPos = blockPos1.offset(xDist * (i + 1) / numSegments, 0, zDist * (i + 1) / numSegments).mutable();
            SplineRoadSegment splineRoadSegment = SplineRoadSegment.createSplineRoadSegment(startPos, endPos, random.get());
            road.addRoadSegment(splineRoadSegment);
        }

        // Biome validation
        int riverCount = 0;
        for (DefaultRoadSegment roadSegment : road.getRoadSegments()) {
            Holder<Biome> biomeAtStart = serverLevel.getChunkSource().getGenerator().getNoiseBiome(
                    QuartPos.fromBlock(roadSegment.getStartPos().getX()),
                    QuartPos.fromBlock(serverLevel.getSeaLevel()),
                    QuartPos.fromBlock(roadSegment.getStartPos().getZ()));
            if (biomeAtStart.is(BiomeTags.IS_RIVER)) riverCount++;

            if (roadSegment instanceof SplineRoadSegment splineRoadSegment) {
                Holder<Biome> biomeAtP2 = serverLevel.getChunkSource().getGenerator().getNoiseBiome(
                        QuartPos.fromBlock(splineRoadSegment.getP2().getX()),
                        QuartPos.fromBlock(serverLevel.getSeaLevel()),
                        QuartPos.fromBlock(splineRoadSegment.getP2().getZ()));
                if (biomeAtP2.is(BiomeTags.IS_RIVER)) riverCount++;
            }

            // Road cannot cross river more than once
            if (riverCount > 1) {
                return Optional.empty();
            }
            // Road cannot cross ocean
            if (biomeAtStart.is(BiomeTags.IS_OCEAN)) {
                return Optional.empty();
            }
        }

        return Optional.of(road);
    }

    @Override
    public void placeRoad(Road road, WorldGenLevel level, Random rand, BlockPos blockPos, RoadFeatureConfiguration config, @Nullable BlockPos nearestVillage) {
        // The position of the chunk we're currently confined to
        ChunkPos chunkPos = new ChunkPos(blockPos);

        // Short-circuit if this chunk isn't between the start/end points of the road
        if (!containsRoad(chunkPos, road)) {
            return;
        }

        // Debug markers at road endpoints points
        if (YungsRoadsCommon.DEBUG_MODE) {
            placeDebugMarker(level, chunkPos, road.getVillageStart(), Blocks.EMERALD_BLOCK.defaultBlockState());
            placeDebugMarker(level, chunkPos, road.getVillageEnd(), Blocks.EMERALD_BLOCK.defaultBlockState());
        }

        // Determine road segments we need to process for this chunk
        List<DefaultRoadSegment> roadSegments = new ArrayList<>();
        for (DefaultRoadSegment roadSegment : road.getRoadSegments()) {
            if (containsRoadSegment(chunkPos, roadSegment)) {
                roadSegments.add(roadSegment);
            }
        }

        // Set seeds
//        random.get().setLargeFeatureSeed(level.getSeed(), road.getVillageStart().getX() >> 4, road.getVillageEnd().getZ() >> 4);

        // Temporary chunk-local carving mask to prevent over-processing a single block
        CarvingMask blockMask = new CarvingMask(level.getHeight(), level.getMinBuildHeight());

        // Place road segments in this chunk
        for (DefaultRoadSegment roadSegment : roadSegments) {
            if ((!(roadSegment instanceof SplineRoadSegment splineRoadSegment))) {
                YungsRoadsCommon.LOGGER.error("Road segment {} is not a SplineRoadSegment!", roadSegment);
                continue;
            }
            Vec3[] pts = splineRoadSegment.getPointsAsVec();

            // Debug markers at road segment endpoints
            if (YungsRoadsCommon.DEBUG_MODE) {
                placeDebugMarker(level, chunkPos, new BlockPos(pts[0].x, pts[0].y, pts[0].z), Blocks.DIAMOND_BLOCK.defaultBlockState());
                placeDebugMarker(level, chunkPos, new BlockPos(pts[3].x, pts[3].y, pts[3].z), Blocks.DIAMOND_BLOCK.defaultBlockState());
            }

            // Begin Bezier curve path placement
            float t = 0;
            int counter = 0;
            Vec3 posVec;
            BlockPos.MutableBlockPos pathPosCenter = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

            while (t <= 1f) {
                posVec = getBezierPoint(pts, t);
                pathPosCenter.set(Math.round(posVec.x), Math.round(posVec.y), Math.round(posVec.z));
                mutable.set(pathPosCenter);

                // Attempt to place path at this position
                if (isInValidRangeForChunk(chunkPos, pathPosCenter)) {
                    if (isInChunk(chunkPos, pathPosCenter)) {
                        placePath(level, rand, pathPosCenter, chunkPos, config, blockMask, nearestVillage);

                        if (counter >= 50 && counter % 50 == 0) {
                            // Attempt placing decoration at this point.
                            // We use normals to find the approximate edge of the road at this point.
                            // The tangent is provided for decorations should they choose to use it.
                            Vec3[] normals = getNormals(pts, t);
                            Vec3 tangent = getTangent(pts, t);

                            for (Vec3 normal : normals) {
                                // Move the mutable to the edge of the road at this position.
                                mutable.set(pathPosCenter);
                                RoadTypeConfig roadTypeConfig = this.getRoadTypeAtPos(level, mutable, config);

                                mutable.move((int) Math.round(normal.x() * (roadTypeConfig.roadSizeRadius + 1)), 0, (int) Math.round(normal.z() * (roadTypeConfig.roadSizeRadius + 1)));
                                mutable.setY(getSurfaceHeight(level, mutable) + 1);

                                BlockState currState = level.getBlockState(mutable);
                                BlockState belowState = level.getBlockState(mutable.below());

                                // Check for water, in which case no decorations are placed.
                                if (belowState.getMaterial() == Material.WATER) {
                                    continue;
                                }

                                // Determine decorations that can be placed at this point.
                                // The decorations list is fetched from a RoadTypeSettings, based on the existing
                                // block at this position.
//                                RoadTypeConfig roadTypeConfig = null;
//                                for (RoadTypeConfig settings : config.roadTypes) {
//                                    if (settings.matches(level, mutable.below())) {
//                                        roadTypeConfig = settings;
//                                        break;
//                                    }
//                                }

//                                if (roadTypeConfig == null) {
//                                    continue;
//                                }

                                // Attempt to place a decoration
                                List<ConfiguredRoadDecoration> decorationsCopy = new ArrayList<>(roadTypeConfig.decorations);

                                while (decorationsCopy.size() > 0) {
                                    ConfiguredRoadDecoration decoration = decorationsCopy.get(decorationsCopy.size() - 1);
                                    if (decoration.place(level, rand, mutable, normal, tangent)) {
//                                        YungsRoadsCommon.LOGGER.info("PLACED {} (t={} counter={})", decoration, t, counter);
                                        break;
                                    }
                                    decorationsCopy.remove(decoration);
                                }

//                            BlockState surfaceBlock = level.getBlockState(mutable);
//                            int seaLevelDistance = mutable.getY() - level.getSeaLevel();
//                            int yCompression = seaLevelDistance / (10 + (3 * normalOffset));
                                // Place air to destroy any floating plants and the like
//                            if (yCompression > 0) {
//                                mutable.move(Direction.UP);
//                                level.setBlock(mutable, Blocks.AIR.defaultBlockState(), 2);
//                                mutable.move(Direction.DOWN);
//                            }
//                            for (int y = 0; y < yCompression; y++) {
//                                level.setBlock(mutable, Blocks.AIR.defaultBlockState(), 2);
//                                mutable.move(Direction.DOWN);
//                            }
//                            level.setBlock(mutable, surfaceBlock, 2);
                            }
                        }

                        // Debug markers
                        if (YungsRoadsCommon.DEBUG_MODE) {
                            if (counter == 200 || counter == 400 || counter == 600 || counter == 800) {
                                placeDebugMarker(level, chunkPos, pathPosCenter, Blocks.GOLD_BLOCK.defaultBlockState());
                            }
                        }
                    }
                }
                t += 0.002f;
                counter++;
            }

            YungsRoadsCommon.LOGGER.debug("Generated {}", roadSegment);
        }
    }

    /**
     * Get point for given t-value.
     * This is a standard Bezier curve implementation.
     */
    private Vec3 getBezierPoint(Vec3[] pts, float t) {
        float omt = 1f - t;
        float omt2 = omt * omt;
        float t2 = t * t;
        return pts[0].scale(omt2 * omt).add(
                pts[1].scale(3f * omt2 * t)).add(
                pts[2].scale(3f * omt * t2)).add(
                pts[3].scale(t2 * t));
    }

    private Vec3 getTangent(Vec3[] pts, float t) {
        double omt = 1f - t;
        double omt2 = omt * omt;
        double t2 = t * t;
        Vec3 tangent = pts[0].scale(-omt2).add(
                pts[1].scale(3f * omt2 - 2 * omt)).add(
                pts[2].scale(-3f * t2 + 2 * t)).add(
                pts[3].scale(t2));
        return tangent.normalize();
    }

    private Vec3 getNormal(Vec3[] pts, float t) {
        return getTangent(pts, t).yRot((float) Math.PI / 2F).normalize();
    }

    private Vec3[] getNormals(Vec3[] pts, float t) {
        Vec3 normal = getNormal(pts, t);
        return new Vec3[]{normal, normal.reverse()};
    }
}