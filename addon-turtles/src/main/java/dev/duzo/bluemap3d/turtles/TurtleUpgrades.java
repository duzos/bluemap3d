package dev.duzo.bluemap3d.turtles;

import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a turtle's equipped upgrades into models BlueMap3D can draw.
 *
 * <p>Upgrades are not in the block state - they live in the block entity and are drawn by
 * CC's own renderer - so a turtle meshed from its block state alone comes out bare. They are
 * read here from the saved data as {@code LeftUpgrade}/{@code RightUpgrade}, each a compound
 * holding an upgrade {@code id}, which keeps this addon off CC's classes exactly like the
 * label and computer id already are.
 *
 * <h2>Two kinds of upgrade, two levels of fidelity</h2>
 * CC models upgrades one of two ways, and the difference matters:
 *
 * <ul>
 *   <li><b>Peripherals</b> use {@code TurtleUpgradeModeller.sided(left, right)}, backed by
 *       real json models CC ships - {@code turtle_modem_normal_off_left},
 *       {@code turtle_speaker_right} and so on. They are authored in the turtle's own 0..16
 *       block space, already positioned against the correct face, so they need no transform
 *       and come out exactly as the game draws them.</li>
 *   <li><b>Tools</b> use {@code TurtleUpgradeModeller.flatItem()}, which asks the client for
 *       the item's baked model. A tool's item model inherits {@code item/handheld} and has
 *       no geometry at all - just a {@code layer0} sprite the client extrudes. There is
 *       nothing to mesh.</li>
 * </ul>
 *
 * <p>So tools fall back to CC's own {@code turtle_upgrade_base} mount plate, textured with
 * the tool's item sprite. Because the mesh is drawn with an alpha test, the sprite's
 * transparent pixels are discarded and what survives is the tool's actual silhouette lying
 * flat against the turtle's side. Not identical to the game - no extrusion, and the mount's
 * uvs crop the sprite slightly - but recognisably the right tool, for a fraction of the work
 * that reimplementing sprite extrusion server-side would take.
 */
final class TurtleUpgrades {

    /** CC's mount plate, used for anything without a model of its own. */
    private static final ResourceLocation MOUNT_LEFT =
            ResourceLocation.fromNamespaceAndPath("computercraft", "block/turtle_upgrade_base_left");
    private static final ResourceLocation MOUNT_RIGHT =
            ResourceLocation.fromNamespaceAndPath("computercraft", "block/turtle_upgrade_base_right");

    /**
     * Upgrade id to model name stem, for the upgrades CC ships real geometry for.
     *
     * <p>Ids confirmed from CC's own datapack under
     * {@code data/*}/computercraft/turtle_upgrade/}. Modems have on and off variants; off is
     * used because whether a modem is transmitting is not visible in the block entity's
     * saved data.
     */
    private static final Map<String, String> PERIPHERAL_MODELS = Map.of(
            "computercraft:wireless_modem_normal", "turtle_modem_normal_off",
            "computercraft:wireless_modem_advanced", "turtle_modem_advanced_off",
            "computercraft:speaker", "turtle_speaker",
            "minecraft:crafting_table", "turtle_crafting_table");

    private TurtleUpgrades() {
    }

    /**
     * The attachments for a turtle's upgrades.
     *
     * @param tag the turtle block entity's saved data
     */
    static List<ModelAttachment> of(CompoundTag tag) {
        List<ModelAttachment> out = new ArrayList<>(2);
        add(out, upgradeId(tag, "LeftUpgrade"), true);
        add(out, upgradeId(tag, "RightUpgrade"), false);
        return out;
    }

    /**
     * A stable summary of the equipped upgrades, to fold into the geometry version.
     *
     * <p>Without this, fitting or removing an upgrade would not re-bake the mesh and the
     * change would never show up.
     */
    static String signature(CompoundTag tag) {
        return upgradeId(tag, "LeftUpgrade") + "|" + upgradeId(tag, "RightUpgrade");
    }

    private static void add(List<ModelAttachment> out, String upgradeId, boolean left) {
        if (upgradeId.isEmpty()) {
            return;
        }
        String stem = PERIPHERAL_MODELS.get(upgradeId);
        if (stem != null) {
            out.add(new ModelAttachment(BlockPos.ZERO,
                    ResourceLocation.fromNamespaceAndPath("computercraft",
                            "block/" + stem + (left ? "_left" : "_right")),
                    Map.of()));
            return;
        }

        // A tool, or a modded upgrade with no model of its own. CC's upgrade ids for tools
        // are the item id itself, so the item model follows directly - and core extrudes it
        // from its sprite, because item models carry no geometry.
        ResourceLocation item = ResourceLocation.tryParse(upgradeId);
        if (item == null) {
            return;
        }
        out.add(new ModelAttachment(BlockPos.ZERO,
                ResourceLocation.fromNamespaceAndPath(item.getNamespace(), "item/" + item.getPath()),
                Map.of(),
                toolTransform(left)));
    }

    /**
     * CC's own placement for a flat item upgrade.
     *
     * <p>Taken from {@code TurtleUpgradeModellers.getMatrixFor}, which builds
     * {@code new Matrix4f().set(float[16])} and calls it with {@code -0.4065} for the left
     * side and {@code +0.4065} for the right. The array puts translation at indices 3, 7 and
     * 11, so read as rows the matrix is:
     *
     * <pre>
     *   (  0   0  -1   1 + offset )
     *   (  1   0   0   0          )
     *   (  0  -1   0   1          )
     *   (  0   0   0   1          )
     * </pre>
     *
     * <p>Which is what turns an item model - authored face-on, in the middle of the block -
     * edge-on against the turtle's left or right side. Copied rather than called: the class
     * holding it touches {@code net.minecraft.client.Minecraft}, so loading it on a server
     * would fail.
     */
    private static Matrix4f toolTransform(boolean left) {
        float offset = left ? -0.4065f : 0.4065f;
        return new Matrix4f(
                // JOML's column-major constructor, so this is the transpose of the rows above.
                0f, 1f, 0f, 0f,
                0f, 0f, -1f, 0f,
                -1f, 0f, 0f, 0f,
                1f + offset, 0f, 1f, 1f);
    }

    /** The upgrade id under a key, or empty if nothing is fitted there. */
    private static String upgradeId(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return "";
        }
        String id = tag.getCompound(key).getString("id");
        return id == null ? "" : id;
    }
}
