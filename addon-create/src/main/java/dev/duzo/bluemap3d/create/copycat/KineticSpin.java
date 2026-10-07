package dev.duzo.bluemap3d.create.copycat;

import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Turns a kinetic block's speed into the {@link ModelAttachment.Rate} that spins its
 * appearance.
 *
 * <p>Shares only the <em>convention</em> with {@link dev.duzo.bluemap3d.create.BearingProvider},
 * not its code. Create's {@code KineticBlockEntityRenderer} always spins a shaft or cog
 * about the positive direction of its axis, with positive RPM turning the positive way, and
 * that is what is reproduced here. The bearing provider's own rate helper does something
 * different on purpose: its axis is in a model's space, and its sign compensates for the
 * reorientation core applies to an attachment, neither of which exists for an appearance. An
 * appearance is already in volume space and is spun about its axis as given.
 */
final class KineticSpin {

    private KineticSpin() {
    }

    /**
     * The rate for a block turning about {@code axis} at {@code rpm}, or {@code null} if it
     * is not turning.
     *
     * <p>Quantised to whole RPM, the same step {@code BearingProvider} uses, so that a
     * speed that jitters by a fraction does not re-mesh a ship. {@link ModelAttachment.Rate}
     * must be positive, so a negative speed is carried as a flipped axis.
     *
     * @param axis the block's axle
     * @param rpm  its speed, as {@code KineticBlockEntity.getSpeed()} reports it
     */
    @Nullable
    static ModelAttachment.Rate rateFor(Direction.Axis axis, float rpm) {
        long quantised = Math.round(rpm);
        if (quantised == 0L) {
            return null;
        }
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        float sign = quantised < 0 ? -1f : 1f;
        Vector3f direction = new Vector3f(positive.getStepX(), positive.getStepY(), positive.getStepZ()).mul(sign);
        // The pivot is required by Rate but not read: the mesher turns an appearance about
        // the middle of its block, built from the block's own position.
        return new ModelAttachment.Rate(new Vector3f(8f, 8f, 8f), direction,
                Math.abs(quantised) * (float) (Math.PI / 30.0));
    }
}
