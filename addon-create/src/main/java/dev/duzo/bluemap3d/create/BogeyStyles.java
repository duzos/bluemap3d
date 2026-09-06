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
 * appears in the block entity's {@code BogeyData/BogeyStyle} tag. That really is the only
 * change needed: {@link ContraptionProvider} dispatches on the style id alone and keeps no
 * list of the blocks these styles can sit on, so nothing there goes stale when a row is
 * added here.
 */
final class BogeyStyles {

    private BogeyStyles() {
    }

    private static ResourceLocation railways(String path) {
        return ResourceLocation.fromNamespaceAndPath("railways", path);
    }

    // -----------------------------------------------------------------------------
    // Heights
    // -----------------------------------------------------------------------------
    //
    // ContraptionProvider's own four Create parts fix the rule this whole file has to
    // follow: a part's vertical offset is its renderer's NET static lift - the y it is
    // left at once every translate in its update() has been applied at angle zero - plus
    // one universal correction. Read off those four:
    //
    //   SMALL_BOGEY_WHEELS  net lift 0.75  -> BOGEY_DROP + SMALL_AXLE_HEIGHT  = -0.75
    //   LARGE_BOGEY_WHEELS  net lift 1.0   -> BOGEY_DROP + LARGE_AXLE_HEIGHT  = -0.5
    //   BOGEY_DRIVE/PISTON  net lift 0     -> BOGEY_DROP + BOGEY_DRIVE_HEIGHT = -1.5
    //   BOGEY_PIN           net lift 1.25  -> BOGEY_DROP + BOGEY_PIN_HEIGHT   = -0.25
    //
    // so offset = net lift - 1.5, and BOGEY_DROP + H is that written as H = net lift - 0.75.
    //
    // "Net" is the word this file previously got wrong, and it is what made both of the
    // user-reported styles sit high. Every one of these families draws its frame with a
    // bare `self()` - no translate at all, net lift 0 - and draws a "lowerPivot" wheel as
    // translate(0, L, z), rotateX(a), translate(0, -L, 0), whose trailing un-translate
    // cancels the lift as well as placing the spin pivot: net lift 0, not L. Both were
    // being given BOGEY_DROP alone, which is the offset for a net lift of 0.75, so every
    // frame and every lowered-pivot wheel in this file floated exactly 0.75 blocks above
    // where Steam 'n' Rails draws it. A wheel with no un-translate (net lift 0.75, the
    // lowerPivot=false rows and the single-axle family) was already right and stays as it
    // was.
    //
    // BOGEY_DRIVE_HEIGHT is reused for the net-lift-0 case rather than restating -0.75,
    // because that is exactly what it is: Create's own gearbox and piston have no static
    // translate either. Deliberately NOT reusing FRAME_HEIGHT for these frames - that
    // constant carries a further empirical nudge for bogey_frame.obj's own baked-in
    // offset, which is a fact about Create's mesh and not about anybody else's.
    private static final float UNLIFTED_HEIGHT = ContraptionProvider.BOGEY_DRIVE_HEIGHT;

    /** The height for a wheel drawn with, or without, the trailing lift-cancelling translate. */
    private static float wheelHeight(boolean lowerPivot) {
        return lowerPivot ? UNLIFTED_HEIGHT : ContraptionProvider.SMALL_AXLE_HEIGHT;
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
    // the model point (0, 0.8125, 0), exactly the "lowerPivot" pattern the double-axle
    // family below also uses. The trailing un-translate does two things at once, and only
    // one of them is the Spin pivot: it also cancels the lift, leaving a net static lift of
    // zero. See the Heights block above - reading it as pivot-only is what put every one of
    // these frames and wheels 0.75 blocks too high.
    //
    // The wheel radius is CRBogeyBlock.getWheelRadius(), 0.40625 in block units - the same
    // 6.5 (in the model's 0..16 space) Create's own small bogey wheel already uses, so
    // WHEEL_RADIUS_SMALL is reused rather than redeclared.
    //
    // The z offsets below were read off decompiled bytecode constants (javap against the
    // eleven standard/medium/*Display classes in the jar), not measured by eye against a
    // running server. Nine of the eleven use a literal per-wheel translate that matches this
    // file's own run(); medium_standard's two wheels and medium_single_wheel's one wheel are
    // the exceptions noted below.

    private static final ResourceLocation MEDIUM_SHARED_WHEELS = railways("block/bogey/medium/shared/wheels");

    /** The wheel's spin pivot, in the model's own 0..16 space: 0.8125 blocks up, times 16. */
    private static final float MEDIUM_WHEEL_PIVOT_Y = 0.8125f * 16f;

    /** Shared by every medium style except medium_single_wheel: net lift 0, see wheelHeight(). */
    private static final float MEDIUM_WHEEL_HEIGHT = ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT;

    // medium_single_wheel's own update() is not the shared double-translate pattern above: it
    // translates to 0.75 (not 0.8125) before rotateX and only -0.8125 after, so the pivot and
    // the net lift both differ from every other medium row - net lift 0.75 - 0.8125 = -0.0625,
    // one pixel below the shared MEDIUM_WHEEL_HEIGHT.
    private static final float SINGLE_WHEEL_PIVOT_Y = 0.75f * 16f;
    private static final float SINGLE_WHEEL_HEIGHT = MEDIUM_WHEEL_HEIGHT - 0.0625f;

    private record MediumStyle(ResourceLocation frame, float[] wheelZOffsets, float wheelPivotY, float wheelHeight) {
        private static MediumStyle of(ResourceLocation frame, float[] wheelZOffsets) {
            return new MediumStyle(frame, wheelZOffsets, MEDIUM_WHEEL_PIVOT_Y, MEDIUM_WHEEL_HEIGHT);
        }
    }

    /** A run of {@code count} wheels 1.5 blocks apart, starting at {@code start}. */
    private static float[] run(int count, float start) {
        return run(count, start, 1.5f);
    }

    /** A run of {@code count} wheels {@code step} blocks apart, starting at {@code start}. */
    private static float[] run(int count, float start, float step) {
        float[] offsets = new float[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = start + i * step;
        }
        return offsets;
    }

    private static final Map<String, MediumStyle> MEDIUM_STYLES = Map.ofEntries(
            Map.entry("railways:medium_single_wheel", new MediumStyle(
                    railways("block/bogey/medium/single_wheel/frame"), new float[]{0f},
                    SINGLE_WHEEL_PIVOT_Y, SINGLE_WHEEL_HEIGHT)),
            Map.entry("railways:medium_standard", MediumStyle.of(
                    railways("block/bogey/medium/standard/frame"), new float[]{-1.0f, 1.0f})),
            Map.entry("railways:medium_2_0_2_trailing", MediumStyle.of(
                    railways("block/bogey/medium/2_0_2_trailing/frame"), new float[]{0f})),
            Map.entry("railways:medium_4_0_4_trailing", MediumStyle.of(
                    railways("block/bogey/medium/4_0_4_trailing/frame"), new float[]{-0.75f, 0.75f})),
            Map.entry("railways:medium_triple_wheel", MediumStyle.of(
                    railways("block/bogey/medium/triple_wheel/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_6_0_6_trailing", MediumStyle.of(
                    railways("block/bogey/medium/6_0_6_trailing/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_6_0_6_tender", MediumStyle.of(
                    railways("block/bogey/medium/6_0_6_tender/frame"), run(3, -1.5f))),
            Map.entry("railways:medium_quadruple_wheel", MediumStyle.of(
                    railways("block/bogey/medium/quadruple_wheel/frame"), run(4, -2.25f))),
            Map.entry("railways:medium_8_0_8_tender", MediumStyle.of(
                    railways("block/bogey/medium/8_0_8_tender/frame"), run(4, -2.25f))),
            Map.entry("railways:medium_quintuple_wheel", MediumStyle.of(
                    railways("block/bogey/medium/quintuple_wheel/frame"), run(5, -3.0f))),
            Map.entry("railways:medium_10_0_10_tender", MediumStyle.of(
                    railways("block/bogey/medium/10_0_10_tender/frame"), run(5, -3.0f)))
    );

    // -----------------------------------------------------------------------------
    // Single axle family
    // -----------------------------------------------------------------------------
    //
    // Three style ids - singleaxle, leafspring, coilspring - and one shared code path.
    // All three `standard/single_axle/*Display` classes are a single-line constructor,
    // `super(provider, FRAME)`; the wheel model (always create:SMALL_BOGEY_WHEELS) and the
    // wheel maths live once in `single_axle/base/SingleAxleBogeyDisplay`: one wheel
    // instance, `translate(0, 0.75, 0)` then `rotateX(a)`, with no un-translate afterwards.
    // That is exactly the lowerPivot=false shape the double-axle family below already
    // covers - a Spin pivoted at the wheel model's own origin, ContraptionProvider's own
    // WHEEL_PIVOT - just with one wheel instance instead of two, since a single-axle bogey
    // has only the one axle.
    //
    // Read by javap, one row per style: singleaxle -> SINGLEAXLE_FRAME, leafspring ->
    // LEAFSPRING_FRAME, coilspring -> COILSPRING_FRAME. All three sit on the same block,
    // railways:singleaxle_bogey.

    private record SingleAxleStyle(ResourceLocation frame) {
    }

    private static final Map<String, SingleAxleStyle> SINGLE_AXLE_STYLES = Map.of(
            "railways:singleaxle", new SingleAxleStyle(railways("block/bogey/singleaxle/frame")),
            "railways:leafspring", new SingleAxleStyle(railways("block/bogey/leafspring/frame")),
            "railways:coilspring", new SingleAxleStyle(railways("block/bogey/coilspring/frame"))
    );

    // -----------------------------------------------------------------------------
    // Display family (double axle)
    // -----------------------------------------------------------------------------
    //
    // Six style ids - archbar, blomberg, freight, modern, passenger, y25 - and one
    // shared code path. Every one of the six `standard/double_axle/*Display` classes in
    // the jar is a single-line constructor: `super(provider, FRAME, WHEELS, lowerPivot)`,
    // with no `update` override of its own - the frame, wheel count (always two, one
    // axle each side) and wheel maths live once in `double_axle/base/
    // DoubleAxleBogeyDisplay`, and three of the six (archbar, blomberg, y25) go through
    // `CrossShaftDoubleAxleBogeyDisplay`, which adds nothing but two cosmetic
    // create:shaft stubs - skipped here for the same reason the medium family's own
    // shaft stubs are.
    //
    // The base's wheel maths, read the same way as ContraptionProvider's own bogey
    // comment block already reads Create's: `translate(0, 0.75, z)`, `rotateX(a)`,
    // `translateY(lowerPivot ? -0.75 : 0)`. z is +-1 block (Iterate.positiveAndNegative),
    // the same SMALL_AXLE_SPACING Create's own small bogey uses, and the trailing
    // translateY is exactly the "lowerPivot" pattern the medium family's own comment
    // above already covers: it is a Spin pivot, not a separate static offset, so
    // lowerPivot true puts the pivot at model y 0.75 (times 16) and lowerPivot false
    // leaves it at the wheel model's own origin - the same origin Create's own
    // SMALL_BOGEY_WHEELS pivot already uses, which is exactly the wheel model archbar
    // and blomberg (lowerPivot false) draw. It settles the height too, not just the
    // pivot: see the Heights block above and wheelHeight(). The wheel radius is the same
    // WHEEL_RADIUS_SMALL every standard-size Steam 'n' Rails bogey shares.
    //
    // Constructor arguments read by javap, one row per style:
    //   archbar    ARCHBAR_FRAME,    SMALL_BOGEY_WHEELS,  lowerPivot=false
    //   blomberg   BLOMBERG_FRAME,   SMALL_BOGEY_WHEELS,  lowerPivot=false
    //   freight    FREIGHT_FRAME,    LONG_SHAFTED_WHEELS, lowerPivot=true
    //   modern     MODERN_FRAME,     LONG_SHAFTED_WHEELS, lowerPivot=true
    //   passenger  PASSENGER_FRAME,  LONG_SHAFTED_WHEELS, lowerPivot=true
    //   y25        Y25_FRAME,        LONG_SHAFTED_WHEELS, lowerPivot=true
    //
    // Block-wise these six are a mixed bag - CRBogeyStyles registers freight, archbar
    // and y25 on railways:large_platform_doubleaxle_bogey and the other three on plain
    // railways:doubleaxle_bogey, an inconsistency in the mod's own registration rather
    // than a typo here. ContraptionProvider dispatches on the style id alone and keeps no
    // list of the blocks these styles can sit on, so neither block needs a mention here for
    // the row below to be picked.

    private static final ResourceLocation LONG_SHAFTED_WHEELS = railways("block/bogey/wheels/long_shaft_wheels");

    /** The lowered wheel pivot, in the model's own 0..16 space: 0.75 blocks up, times 16. */
    private static final float DOUBLE_AXLE_WHEEL_PIVOT_Y = 0.75f * 16f;

    private record DoubleAxleStyle(ResourceLocation frame, ResourceLocation wheels, boolean lowerPivot) {
    }

    private static final Map<String, DoubleAxleStyle> DOUBLE_AXLE_STYLES = Map.of(
            "railways:archbar", new DoubleAxleStyle(
                    railways("block/bogey/archbar/frame"), ContraptionProvider.SMALL_BOGEY_WHEEL_MODEL, false),
            "railways:blomberg", new DoubleAxleStyle(
                    railways("block/bogey/blomberg/frame"), ContraptionProvider.SMALL_BOGEY_WHEEL_MODEL, false),
            "railways:freight", new DoubleAxleStyle(
                    railways("block/bogey/freight/frame"), LONG_SHAFTED_WHEELS, true),
            "railways:modern", new DoubleAxleStyle(
                    railways("block/bogey/modern/frame"), LONG_SHAFTED_WHEELS, true),
            "railways:passenger", new DoubleAxleStyle(
                    railways("block/bogey/passenger/frame"), LONG_SHAFTED_WHEELS, true),
            "railways:y25", new DoubleAxleStyle(
                    railways("block/bogey/y25/frame"), LONG_SHAFTED_WHEELS, true)
    );

    // -----------------------------------------------------------------------------
    // Triple axle family
    // -----------------------------------------------------------------------------
    //
    // Two style ids - heavyweight, radial - and one shared code path. Both
    // `standard/triple_axle/*Display` classes are a single-line constructor,
    // `super(provider, FRAME, WHEELS, lowerPivot)`; the wheel count (always three) and
    // wheel maths live once in `triple_axle/base/TripleAxleBogeyDisplay`: three wheel
    // instances at `translate(0, 0.75, (i - 1) * 1.5)` for i in 0..2 - the same
    // run(3, -1.5f) z spacing the medium family's own triple-wheel rows already use -
    // then `rotateX(a)`, then `translateY(lowerPivot ? -0.75 : 0)`. That trailing
    // translateY is exactly the double-axle family's own lowerPivot pattern below, just
    // with three axles instead of two.
    //
    // Read by javap, one row per style:
    //   heavyweight  HEAVYWEIGHT_FRAME, LONG_SHAFTED_WHEELS, lowerPivot=true
    //   radial       RADIAL_FRAME,      SMALL_BOGEY_WHEELS,  lowerPivot=false
    //
    // Both sit on the same block, railways:tripleaxle_bogey.

    private record TripleAxleStyle(ResourceLocation frame, ResourceLocation wheels, boolean lowerPivot) {
    }

    private static final Map<String, TripleAxleStyle> TRIPLE_AXLE_STYLES = Map.of(
            "railways:heavyweight", new TripleAxleStyle(
                    railways("block/bogey/heavyweight/frame"), LONG_SHAFTED_WHEELS, true),
            "railways:radial", new TripleAxleStyle(
                    railways("block/bogey/radial/frame"), ContraptionProvider.SMALL_BOGEY_WHEEL_MODEL, false)
    );

    // -----------------------------------------------------------------------------
    // Large Create-styled family
    // -----------------------------------------------------------------------------
    //
    // Five style ids - 0-4-0 through 0-12-0 - and one shared code path, generalising
    // Create's own large bogey (see ContraptionProvider's bogey comment block for the
    // piston/pin maths this reuses verbatim) to N driver axles. Every one of the five
    // `standard/large/LargeCreateStyled*Display` classes has the same shape: a frame, a
    // piston, up to two "full blind" and two "semi blind" wheel instances (unflanged
    // middle axles a real locomotive needs for curves this tight), always two driver
    // wheels (create:LARGE_BOGEY_WHEELS, the same model and radius Create's own large
    // bogey draws), and one pin per axle. Cosmetic create:shaft stubs, same as every
    // other family here, are skipped.
    //
    // Every wheel instance already draws a whole axle (bogey_wheel-style meshes mirror
    // both wheels of an axle across their own local origin, per ContraptionProvider's
    // own comment on WHEEL_PIVOT), so a "semi blind" or "full blind" row of one entry is
    // one axle, not one wheel - the single-instance middle axles below (0-6-0's one
    // semi-blind axle, 0-10-0's one full-blind axle) are not a smaller special case, just
    // an array of length one.
    //
    // The driver wheels and pins read exactly like Create's own large bogey - translate
    // (0, 1, z) then rotateX(a) for a driver wheel, the same but z-offset and with the
    // trailing translate(0, 0.25, 0) rotateX(-a) orbit for a pin - so LARGE_AXLE_HEIGHT,
    // BOGEY_PIN_HEIGHT, PIN_ORBIT_RADIUS and WHEEL_RADIUS_LARGE are reused unchanged; the
    // only new number is z. Semi-blind and full-blind wheels are the same "lowerPivot"
    // shape the double-axle family above already uses - translate(0, 1, z), rotateX(a),
    // translate(0, -1, 0) - so they share LARGE_AXLE_HEIGHT too, just with a Spin pivot
    // at model y 1.0 (times 16) instead of the driver wheel's own origin.
    //
    // The frame and piston sit at exactly the same height as Create's own BOGEY_DRIVE and
    // BOGEY_PISTON, and for exactly the same reason: neither carries a static translate
    // before the piston's oscillation term, so both have a net static lift of zero. An
    // earlier reading of this had them at BOGEY_DROP alone, on the theory that these obj
    // meshes were authored at the right rest height already - they are not, and that put
    // the whole superstructure 0.75 blocks above the wheels it belongs to.
    //
    // The z offsets below were read off decompiled bytecode constants for all five
    // classes, not measured by eye against a running server - expect the same kind of
    // correction pass the medium family's own z offsets already call for.

    private static final ResourceLocation LC_STYLE_SEMI_BLIND_WHEELS =
            railways("block/bogey/large/wheels/semi_blind_wheels");
    private static final ResourceLocation LC_STYLE_FULL_BLIND_WHEELS =
            railways("block/bogey/large/wheels/full_blind_wheels");

    /** The lowered wheel pivot for a semi- or full-blind axle, in the model's own 0..16 space. */
    private static final Vector3f LC_STYLE_LOWERED_PIVOT = new Vector3f(0f, 16f, 0f);

    private record LargeCreateStyle(ResourceLocation frame, ResourceLocation piston,
                                     float[] driverZ, float[] semiBlindZ, float[] fullBlindZ, float[] pinZ) {
    }

    /** {@code {v, -v}} - the driver and semi/full-blind wheels always come in a symmetric pair. */
    private static float[] symmetric(float v) {
        return new float[]{v, -v};
    }

    private static final Map<String, LargeCreateStyle> LARGE_CREATE_STYLES = Map.of(
            "railways:large_create_style_0_4_0", new LargeCreateStyle(
                    railways("block/bogey/large/create_styled_0_4_0/frame/frame"),
                    railways("block/bogey/large/create_styled_0_4_0/piston/piston"),
                    symmetric(0.8732f), new float[0], new float[0],
                    symmetric(0.8732f)),
            "railways:large_create_style_0_6_0", new LargeCreateStyle(
                    railways("block/bogey/large/create_styled_0_6_0/frame/frame"),
                    railways("block/bogey/large/create_styled_0_6_0/piston/piston"),
                    symmetric(1.6842f), new float[]{0f}, new float[0],
                    run(3, -1.6842f, 1.6842f)),
            "railways:large_create_style_0_8_0", new LargeCreateStyle(
                    railways("block/bogey/large/create_styled_0_8_0/frame/frame"),
                    railways("block/bogey/large/create_styled_0_8_0/piston/piston"),
                    symmetric(2.62f), symmetric(0.8732f), new float[0],
                    run(4, -2.62f, 1.7467f)),
            "railways:large_create_style_0_10_0", new LargeCreateStyle(
                    railways("block/bogey/large/create_styled_0_10_0/frame/frame"),
                    railways("block/bogey/large/create_styled_0_10_0/piston/piston"),
                    symmetric(3.3684f), symmetric(1.684f), new float[]{0f},
                    run(5, -3.3684f, 1.6842f)),
            "railways:large_create_style_0_12_0", new LargeCreateStyle(
                    railways("block/bogey/large/create_styled_0_12_0/frame/frame"),
                    railways("block/bogey/large/create_styled_0_12_0/piston/piston"),
                    symmetric(4.3665f), symmetric(2.62f), symmetric(0.8733f),
                    run(6, -4.36641f, 1.74657f))
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
        SingleAxleStyle singleAxle = SINGLE_AXLE_STYLES.get(styleId);
        if (singleAxle != null) {
            return singleAxleAttachments(pos, axis, singleAxle);
        }
        DoubleAxleStyle doubleAxle = DOUBLE_AXLE_STYLES.get(styleId);
        if (doubleAxle != null) {
            return doubleAxleAttachments(pos, axis, doubleAxle);
        }
        TripleAxleStyle tripleAxle = TRIPLE_AXLE_STYLES.get(styleId);
        if (tripleAxle != null) {
            return tripleAxleAttachments(pos, axis, tripleAxle);
        }
        LargeCreateStyle largeCreateStyled = LARGE_CREATE_STYLES.get(styleId);
        if (largeCreateStyled != null) {
            return largeCreateStyledAttachments(pos, axis, largeCreateStyled);
        }
        return List.of();
    }

    private static List<ModelAttachment> mediumAttachments(BlockPos pos, Direction.Axis axis, MediumStyle style) {
        List<ModelAttachment> out = new ArrayList<>();
        out.add(new ModelAttachment(pos, style.frame(), Map.of(),
                ContraptionProvider.bogeyTransform(axis,
                        ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT, 0f)));
        for (float z : style.wheelZOffsets()) {
            // Every medium wheel is a lowered-pivot one - translate(0, pivotY, z), rotateX(a),
            // translate(0, -0.8125, 0) - so its net lift is the pivot minus 0.8125, zero for
            // every style except medium_single_wheel (see SINGLE_WHEEL_HEIGHT above).
            Matrix4f transform = ContraptionProvider.bogeyTransform(axis, style.wheelHeight(), z);
            ModelAttachment.Spin spin = new ModelAttachment.Spin(
                    new Vector3f(0f, style.wheelPivotY(), 0f),
                    ContraptionProvider.WHEEL_AXIS,
                    ContraptionProvider.WHEEL_RADIUS_SMALL);
            out.add(new ModelAttachment(pos, MEDIUM_SHARED_WHEELS, Map.of(), transform, spin));
        }
        return out;
    }

    private static List<ModelAttachment> singleAxleAttachments(BlockPos pos, Direction.Axis axis,
                                                                SingleAxleStyle style) {
        List<ModelAttachment> out = new ArrayList<>();
        out.add(new ModelAttachment(pos, style.frame(), Map.of(),
                ContraptionProvider.bogeyTransform(axis,
                        ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT, 0f)));
        Matrix4f transform = ContraptionProvider.bogeyTransform(axis,
                ContraptionProvider.BOGEY_DROP + ContraptionProvider.SMALL_AXLE_HEIGHT, 0f);
        ModelAttachment.Spin spin = new ModelAttachment.Spin(
                ContraptionProvider.WHEEL_PIVOT, ContraptionProvider.WHEEL_AXIS, ContraptionProvider.WHEEL_RADIUS_SMALL);
        out.add(new ModelAttachment(pos, ContraptionProvider.SMALL_BOGEY_WHEEL_MODEL, Map.of(), transform, spin));
        return out;
    }

    private static List<ModelAttachment> tripleAxleAttachments(BlockPos pos, Direction.Axis axis,
                                                                TripleAxleStyle style) {
        List<ModelAttachment> out = new ArrayList<>();
        out.add(new ModelAttachment(pos, style.frame(), Map.of(),
                ContraptionProvider.bogeyTransform(axis,
                        ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT, 0f)));
        Vector3f pivot = style.lowerPivot()
                ? new Vector3f(0f, DOUBLE_AXLE_WHEEL_PIVOT_Y, 0f)
                : ContraptionProvider.WHEEL_PIVOT;
        for (float z : run(3, -1.5f)) {
            Matrix4f transform = ContraptionProvider.bogeyTransform(axis,
                    ContraptionProvider.BOGEY_DROP + wheelHeight(style.lowerPivot()), z);
            ModelAttachment.Spin spin = new ModelAttachment.Spin(
                    pivot, ContraptionProvider.WHEEL_AXIS, ContraptionProvider.WHEEL_RADIUS_SMALL);
            out.add(new ModelAttachment(pos, style.wheels(), Map.of(), transform, spin));
        }
        return out;
    }

    private static List<ModelAttachment> doubleAxleAttachments(BlockPos pos, Direction.Axis axis,
                                                                DoubleAxleStyle style) {
        List<ModelAttachment> out = new ArrayList<>();
        out.add(new ModelAttachment(pos, style.frame(), Map.of(),
                ContraptionProvider.bogeyTransform(axis,
                        ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT, 0f)));
        Vector3f pivot = style.lowerPivot()
                ? new Vector3f(0f, DOUBLE_AXLE_WHEEL_PIVOT_Y, 0f)
                : ContraptionProvider.WHEEL_PIVOT;
        for (float z : new float[]{ContraptionProvider.SMALL_AXLE_SPACING, -ContraptionProvider.SMALL_AXLE_SPACING}) {
            Matrix4f transform = ContraptionProvider.bogeyTransform(axis,
                    ContraptionProvider.BOGEY_DROP + wheelHeight(style.lowerPivot()), z);
            ModelAttachment.Spin spin = new ModelAttachment.Spin(
                    pivot, ContraptionProvider.WHEEL_AXIS, ContraptionProvider.WHEEL_RADIUS_SMALL);
            out.add(new ModelAttachment(pos, style.wheels(), Map.of(), transform, spin));
        }
        return out;
    }

    private static List<ModelAttachment> largeCreateStyledAttachments(BlockPos pos, Direction.Axis axis,
                                                                       LargeCreateStyle style) {
        List<ModelAttachment> out = new ArrayList<>();

        // The frame is a bare self() and the piston's only translate is its own
        // oscillation term, so both have a net lift of zero - the same net lift Create's
        // own BOGEY_DRIVE and BOGEY_PISTON have, and therefore the same height.
        Matrix4f frameTransform = ContraptionProvider.bogeyTransform(axis,
                ContraptionProvider.BOGEY_DROP + UNLIFTED_HEIGHT, 0f);
        out.add(new ModelAttachment(pos, style.frame(), Map.of(), frameTransform));

        ModelAttachment.Oscillate pistonMotion = new ModelAttachment.Oscillate(
                new Vector3f(0f, 0f, 1f),
                ContraptionProvider.PISTON_STROKE * 16f,
                ContraptionProvider.WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, style.piston(), Map.of(), frameTransform, pistonMotion));

        for (float z : style.driverZ()) {
            out.add(largeWheel(pos, axis, ContraptionProvider.LARGE_BOGEY_WHEEL_MODEL, z,
                    ContraptionProvider.WHEEL_PIVOT, ContraptionProvider.LARGE_AXLE_HEIGHT));
        }
        // A blind axle is translate(0, 1, z), rotateX(a), translate(0, -1, 0): the same
        // lowered-pivot shape as every other family here, so a net lift of zero rather
        // than the driver wheels' 1.0.
        for (float z : style.semiBlindZ()) {
            out.add(largeWheel(pos, axis, LC_STYLE_SEMI_BLIND_WHEELS, z, LC_STYLE_LOWERED_PIVOT,
                    UNLIFTED_HEIGHT));
        }
        for (float z : style.fullBlindZ()) {
            out.add(largeWheel(pos, axis, LC_STYLE_FULL_BLIND_WHEELS, z, LC_STYLE_LOWERED_PIVOT,
                    UNLIFTED_HEIGHT));
        }
        for (float z : style.pinZ()) {
            Matrix4f transform = ContraptionProvider.bogeyTransform(axis,
                    ContraptionProvider.BOGEY_DROP + ContraptionProvider.BOGEY_PIN_HEIGHT, z);
            ModelAttachment.Orbit pinMotion = new ModelAttachment.Orbit(
                    new Vector3f(0f, -ContraptionProvider.PIN_ORBIT_RADIUS * 16f, 0f),
                    ContraptionProvider.WHEEL_AXIS, ContraptionProvider.WHEEL_RADIUS_LARGE);
            out.add(new ModelAttachment(pos, ContraptionProvider.BOGEY_PIN_MODEL, Map.of(), transform, pinMotion));
        }
        return out;
    }

    private static ModelAttachment largeWheel(BlockPos pos, Direction.Axis axis, ResourceLocation model,
                                               float z, Vector3f pivot, float height) {
        Matrix4f transform = ContraptionProvider.bogeyTransform(axis,
                ContraptionProvider.BOGEY_DROP + height, z);
        ModelAttachment.Spin spin = new ModelAttachment.Spin(
                pivot, ContraptionProvider.WHEEL_AXIS, ContraptionProvider.WHEEL_RADIUS_LARGE);
        return new ModelAttachment(pos, model, Map.of(), transform, spin);
    }
}
