package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The style-id-to-geometry table for bogeys from Steam 'n' Rails ({@code railways}),
 * a Create addon whose bogey styles are not the same as Create's own.
 *
 * <p>Kept out of {@link ContraptionProvider} on purpose: that file already carries the
 * hard-won reasoning for Create's own standard bogey in one long comment block, and this
 * table is going to grow to three dozen rows across several families as more of them are
 * added. {@link ContraptionProvider#addBogeyAttachments} calls in here for any style that
 * is not {@code create:standard}; everything Create-specific stays exactly where it was.
 *
 * <p>Same server-safety trap as Create's own bogeys, and the same answer: Steam 'n' Rails
 * builds every one of its styles through Create's {@code BogeyStyle.Builder}, so the
 * renderer that actually knows where each part goes only exists client-side. Nothing here
 * is read from the mod at runtime - every model name, wheel count and offset below was
 * read out of the jar by hand (decompiled bytecode and the model json/obj files
 * themselves) and is simply transcribed as data. An id this table does not recognise, or a
 * family not yet added, draws nothing - never a guess.
 *
 * <p>To add a new style: add one row to the family's map (or a new family's map and a new
 * branch in {@link #attachmentsFor}), keyed on the exact {@code railways:<id>} string that
 * appears in the block entity's {@code BogeyData/BogeyStyle} tag. No other change is
 * needed - the block-id gate in {@link ContraptionProvider} only needs to recognise the
 * block the new style can sit on, which for the medium family is already done for all six.
 */
final class BogeyStyles {

    private BogeyStyles() {
    }

    private static ResourceLocation railways(String path) {
        return ResourceLocation.fromNamespaceAndPath("railways", path);
    }

    // -----------------------------------------------------------------------------
    // Medium family
    // -----------------------------------------------------------------------------
    //
    // Eleven style ids, one shared code path. Every one of the eleven `standard/medium/
    // *Display` classes in the jar has the same field shape (a frame, N wheels, N cosmetic
    // shaft stubs) and the same `update` body up to three numbers: the frame model, the
    // wheel count, and where along z each wheel sits. The shaft stubs are skipped - same
    // decision Create's own standard bogey already made for its plain create:shaft rods,
    // and for the same reason: they are two-pixel cosmetic stubs, not worth a whole extra
    // transform per style for.
    //
    // Every style uses the same wheel model, MEDIUM_SHARED_WHEELS, and the same wheel
    // maths: translate(0, 0.8125, z), rotateX(a), translate(0, -0.8125, 0) - a spin about
    // the model point (0, 0.8125, 0), exactly the "lowerPivot" pattern this addon's own
    // double-axle bogey reasoning already covers (see ContraptionProvider's bogey comment
    // block): Spin's own pivot parameter says where that point is, so the un-translate
    // never needs expressing separately.
    //
    // The wheel radius is CRBogeyBlock.getWheelRadius(), 0.40625 in block units - the same
    // 6.5 (in the model's 0..16 space) Create's own small bogey wheel already uses, so
    // WHEEL_RADIUS_SMALL is reused rather than redeclared.
    //
    // The z offsets below were read off decompiled bytecode constants, not measured by eye
    // against a running server. Two of the eleven (the eyes marked with an explicit array
    // rather than a symmetric run) use Iterate.positiveAndNegative internally rather than a
    // literal start, and the four- and five-wheel rows extend the three-wheel row's start
    // by the same 1.5 step even though that does not come out symmetric about the frame's
    // centre - which is exactly the kind of number this table cannot get from bytecode
    // alone. Expect a correction pass once each is checked against a live render, the same
    // empirical tuning BOGEY_DROP itself needed.

    private static final ResourceLocation MEDIUM_SHARED_WHEELS = railways("block/bogey/medium/shared/wheels");

    /** The wheel's spin pivot, in the model's own 0..16 space: 0.8125 blocks up, times 16. */
    private static final float MEDIUM_WHEEL_PIVOT_Y = 0.8125f * 16f;

    private record MediumStyle(ResourceLocation frame, float[] wheelZOffsets) {
    }

    /** A run of {@code count} wheels 1.5 blocks apart, starting at {@code start}. */
    private static float[] run(int count, float start) {
        float[] offsets = new float[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = start + i * 1.5f;
        }
        return offsets;
    }

    private static final Map<String, MediumStyle> MEDIUM_STYLES = Map.ofEntries(
            Map.entry("railways:medium_single_wheel", new MediumStyle(
                    railways("block/bogey/medium/single_wheel/frame"), new float[]{0f})),
            Map.entry("railways:medium_standard", new MediumStyle(
                    railways("block/bogey/medium/standard/frame"), new float[]{-0.8125f, 0.8125f})),
            Map.entry("railways:medium_2_0_2_trailing", new MediumStyle(
                    railways("block/bogey/medium/2_0_2_trailing/frame"), new float[]{0f})),
            Map.entry("railways:medium_4_0_4_trailing", new MediumStyle(
                    railways("block/bogey/medium/4_0_4_trailing/frame"), new float[]{-0.75f, 0.75f})),
            Map.entry("railways:medium_triple_wheel", new MediumStyle(
                    railways("block/bogey/medium/triple_wheel/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_6_0_6_trailing", new MediumStyle(
                    railways("block/bogey/medium/6_0_6_trailing/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_6_0_6_tender", new MediumStyle(
                    railways("block/bogey/medium/6_0_6_tender/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_quadruple_wheel", new MediumStyle(
                    railways("block/bogey/medium/quadruple_wheel/frame"), run(4, -0.75f))),
            Map.entry("railways:medium_8_0_8_tender", new MediumStyle(
                    railways("block/bogey/medium/8_0_8_tender/frame"), run(4, -0.75f))),
            Map.entry("railways:medium_quintuple_wheel", new MediumStyle(
                    railways("block/bogey/medium/quintuple_wheel/frame"), run(5, -1.5f))),
            Map.entry("railways:medium_10_0_10_tender", new MediumStyle(
                    railways("block/bogey/medium/10_0_10_tender/frame"), run(5, -1.5f)))
    );

    /**
     * The parts for one bogey style at {@code pos}, or an empty list for any style this
     * table does not (yet) recognise - the same draw-nothing fallback
     * {@link ContraptionProvider} already used for every non-Create style before this
     * table existed, now just delegated to.
     */
    static List<ModelAttachment> attachmentsFor(String styleId, BlockPos pos, Direction.Axis axis) {
        MediumStyle medium = MEDIUM_STYLES.get(styleId);
        if (medium != null) {
            return mediumAttachments(pos, axis, medium);
        }
        return List.of();
    }

    private static List<ModelAttachment> mediumAttachments(BlockPos pos, Direction.Axis axis, MediumStyle style) {
        List<ModelAttachment> out = new ArrayList<>();
        out.add(new ModelAttachment(pos, style.frame(), Map.of(),
                ContraptionProvider.bogeyTransform(axis, ContraptionProvider.BOGEY_DROP, 0f)));
        for (float z : style.wheelZOffsets()) {
            Matrix4f transform = ContraptionProvider.bogeyTransform(axis, ContraptionProvider.BOGEY_DROP, z);
            ModelAttachment.Spin spin = new ModelAttachment.Spin(
                    new Vector3f(0f, MEDIUM_WHEEL_PIVOT_Y, 0f),
                    ContraptionProvider.WHEEL_AXIS,
                    ContraptionProvider.WHEEL_RADIUS_SMALL);
            out.add(new ModelAttachment(pos, MEDIUM_SHARED_WHEELS, Map.of(), transform, spin));
        }
        return out;
    }
}
