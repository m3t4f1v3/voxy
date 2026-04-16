package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.LiquidBlock;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    private static final int[] CUBE_INDEX_TO_Y = new int[]{0, 0, 0, 0, 1, 1, 1, 1};

    private static boolean hasFluid(long state, Mapper mapper) {
        return !mapper.getBlockStateFromBlockId(Mapper.getBlockId(state)).getFluidState().isEmpty();
    }

    private static boolean isPureFluid(long state, Mapper mapper) {
        return mapper.getBlockStateFromBlockId(Mapper.getBlockId(state)).getBlock() instanceof LiquidBlock;
    }

    private static boolean isFullOpaque(long state, Mapper mapper) {
        return mapper.getBlockStateOpacity(state) >= 15;
    }

    private static int pickRepresentative(long[] states, int blockId, int preferredY, Mapper mapper, boolean requireFluid) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state) || Mapper.getBlockId(state) != blockId) {
                continue;
            }
            if (requireFluid && !hasFluid(state, mapper)) {
                continue;
            }
            int score = 0;
            score += CUBE_INDEX_TO_Y[i] == preferredY ? 128 : 0;
            score += isPureFluid(state, mapper) ? 32 : 0;
            score += Mapper.getLightId(state);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int chooseVisibleFluid(long[] states, Mapper mapper) {
        int fluidLayer = -1;
        for (int y = 1; y >= 0 && fluidLayer == -1; y--) {
            for (int i = 0; i < states.length; i++) {
                if (CUBE_INDEX_TO_Y[i] != y || Mapper.isAir(states[i])) {
                    continue;
                }
                if (hasFluid(states[i], mapper)) {
                    fluidLayer = y;
                    break;
                }
            }
        }
        if (fluidLayer == -1) {
            return -1;
        }

        for (int i = 0; i < states.length; i++) {
            if (CUBE_INDEX_TO_Y[i] > fluidLayer && !Mapper.isAir(states[i]) && isFullOpaque(states[i], mapper)) {
                return -1;
            }
        }

        int bestFluidBlockId = -1;
        int bestFluidScore = Integer.MIN_VALUE;
        int opaqueScore = 0;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state) || CUBE_INDEX_TO_Y[i] != fluidLayer) {
                continue;
            }
            if (hasFluid(state, mapper)) {
                int blockId = Mapper.getBlockId(state);
                int score = 0;
                for (int j = 0; j < states.length; j++) {
                    long other = states[j];
                    if (Mapper.isAir(other) || CUBE_INDEX_TO_Y[j] != fluidLayer || Mapper.getBlockId(other) != blockId || !hasFluid(other, mapper)) {
                        continue;
                    }
                    score += isPureFluid(other, mapper) ? 8 : 6;
                }
                if (score > bestFluidScore) {
                    bestFluidScore = score;
                    bestFluidBlockId = blockId;
                }
            } else if (isFullOpaque(state, mapper)) {
                opaqueScore += 6;
            }
        }

        if (bestFluidBlockId == -1 || opaqueScore > bestFluidScore) {
            return -1;
        }
        return pickRepresentative(states, bestFluidBlockId, fluidLayer, mapper, true);
    }

    private static int chooseDominantState(long[] states, Mapper mapper) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state)) {
                continue;
            }
            int blockId = Mapper.getBlockId(state);
            int count = 0;
            int highestY = 0;
            for (int j = 0; j < states.length; j++) {
                long other = states[j];
                if (!Mapper.isAir(other) && Mapper.getBlockId(other) == blockId) {
                    count++;
                    highestY = Math.max(highestY, CUBE_INDEX_TO_Y[j]);
                }
            }

            int score = 0;
            score += count << 8;
            score += mapper.getBlockStateOpacity(blockId) << 3;
            score += highestY << 2;
            score += hasFluid(state, mapper) ? 2 : 0;
            score += isPureFluid(state, mapper) ? 1 : 0;

            if (score > bestScore) {
                bestScore = score;
                bestIndex = pickRepresentative(states, blockId, highestY, mapper, false);
            }
        }
        return bestIndex;
    }

    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        long[] states = new long[]{I000, I100, I001, I101, I010, I110, I011, I111};

        int visibleFluid = chooseVisibleFluid(states, mapper);
        if (visibleFluid != -1) {
            return states[visibleFluid];
        }

        int dominantState = chooseDominantState(states, mapper);
        if (dominantState != -1) {
            return states[dominantState];
        } else {
            int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                    (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
            int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                    (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
            blockLight = blockLight / 8;
            skyLight = (int) Math.ceil((double) skyLight / 8);

            return withLight(I111, (blockLight << 4) | skyLight);
        }
    }
}
