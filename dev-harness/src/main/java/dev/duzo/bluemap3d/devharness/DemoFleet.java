package dev.duzo.bluemap3d.devharness;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * Four fake turtles wandering a superflat, for seeing the pipeline work in a browser.
 *
 * <p>This is the cheap end-to-end test. It exercises everything that matters without
 * needing CC:Tweaked, Sable or Create installed: geometry gets meshed and published once,
 * transforms stream every interval, and the browser interpolates between them. If these
 * four move smoothly and are textured, the framework works and the real addons are only a
 * matter of reading each mod's data.
 *
 * <p>They move continuously at a fraction of a block per tick rather than hopping between
 * blocks, which is deliberate: block-snapping would look fine even if interpolation were
 * broken, and the point of the test is to catch exactly that.
 */
public final class DemoFleet implements SceneObjectProvider {

    /** Blocks per tick. About 1.5 blocks a second, a comfortable walking pace. */
    private static final double SPEED = 0.075;
    /** How far from the origin they are allowed to wander. */
    private static final double RADIUS = 24.0;
    /** Radians per tick of turn. Gentle, so a turn is visibly interpolated. */
    private static final double TURN_RATE = 0.06;

    private final List<Bot> bots = new ArrayList<>();
    private final Random random = new Random(20260801L);

    public DemoFleet() {
        // A mix of directional blocks, so rotation is obvious and the texture atlas has
        // more than one sprite to pack.
        add("Quarry-01", Blocks.OBSERVER.defaultBlockState(), 6, 0);
        add("Quarry-02", Blocks.DISPENSER.defaultBlockState(), -6, 6);
        add("Hauler-03", Blocks.LOOM.defaultBlockState(), 0, -8);
        add("Scout-04", Blocks.BEEHIVE.defaultBlockState(), 8, 8);
    }

    private void add(String label, BlockState state, double x, double z) {
        // North-facing, because rotation is streamed rather than baked - the same trick
        // the real turtle addon uses so one mesh serves every facing.
        BlockState upright = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                : state;
        Bot bot = new Bot(label, upright);
        bot.x = x;
        bot.z = z;
        bot.yaw = random.nextDouble() * Math.PI * 2;
        bot.targetYaw = bot.yaw;
        bots.add(bot);
    }

    /** Steps the simulation. Called once per server tick. */
    public void tick() {
        for (Bot bot : bots) {
            if (random.nextInt(60) == 0) {
                bot.targetYaw = random.nextDouble() * Math.PI * 2;
            }

            // Turn towards the target by at most TURN_RATE, the short way round.
            double delta = wrapAngle(bot.targetYaw - bot.yaw);
            bot.yaw += Math.max(-TURN_RATE, Math.min(TURN_RATE, delta));

            double nextX = bot.x - Math.sin(bot.yaw) * SPEED;
            double nextZ = bot.z + Math.cos(bot.yaw) * SPEED;

            if (nextX * nextX + nextZ * nextZ > RADIUS * RADIUS) {
                // Turn back towards the middle rather than walking off forever.
                bot.targetYaw = Math.atan2(-bot.x, bot.z) + Math.PI;
            } else {
                bot.x = nextX;
                bot.z = nextZ;
            }
        }
    }

    @Override
    public String id() {
        return "devharness_demo";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        // Overworld only, so switching maps in the browser visibly hides them - which is
        // the dimension-filtering path being tested too.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return List.of();
        }
        ResourceKey<Level> dimension = level.dimension();
        // Default 1.21 superflat is bedrock at y=-64, two dirt, grass at y=-61, so the
        // walkable surface is y=-60 and a block sitting on it has its centre at -59.5.
        double y = -59.5;

        List<SceneObject> out = new ArrayList<>(bots.size());
        for (Bot bot : bots) {
            Vec3 position = new Vec3(bot.x, y, bot.z);
            Quaternionf rotation = new Quaternionf().rotateY((float) bot.yaw);
            out.add(new SceneObject() {
                @Override public String id() {
                    return bot.label;
                }
                @Override public BlockVolume geometry() {
                    return BlockVolume.single(bot.state);
                }
                @Override public long geometryVersion() {
                    return 1L;
                }
                @Override public Vec3 position() {
                    return position;
                }
                @Override public Quaternionf rotation() {
                    return rotation;
                }
                @Override public String label() {
                    return bot.label;
                }
                @Override public ResourceKey<Level> dimension() {
                    return dimension;
                }
            });
        }
        return out;
    }

    private static double wrapAngle(double radians) {
        double wrapped = radians % (Math.PI * 2);
        if (wrapped > Math.PI) {
            wrapped -= Math.PI * 2;
        } else if (wrapped < -Math.PI) {
            wrapped += Math.PI * 2;
        }
        return wrapped;
    }

    private static final class Bot {
        final String label;
        final BlockState state;
        double x;
        double z;
        double yaw;
        double targetYaw;

        Bot(String label, BlockState state) {
            this.label = label;
            this.state = state;
        }
    }
}
