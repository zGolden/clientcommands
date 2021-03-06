package net.earthcomputer.clientcommands.script;

import com.google.common.collect.ImmutableSet;
import jdk.nashorn.api.scripting.JSObject;
import net.earthcomputer.clientcommands.MathUtil;
import net.earthcomputer.clientcommands.features.PathfindingHints;
import net.earthcomputer.clientcommands.features.PlayerPathfinder;
import net.earthcomputer.clientcommands.interfaces.IBlockChangeListener;
import net.earthcomputer.clientcommands.interfaces.IMinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.container.Container;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.network.packet.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class ScriptPlayer extends ScriptLivingEntity {

    ScriptPlayer() {
        super(getPlayer());
    }

    private static ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    @Override
    ClientPlayerEntity getEntity() {
        return getPlayer();
    }

    public boolean snapTo(double x, double y, double z) {
        return snapTo(x, y, z, false);
    }

    public boolean snapTo(double x, double y, double z, boolean sync) {
        double dx = x - getX();
        double dy = y - getY();
        double dz = z - getZ();
        if (dx * dx + dy * dy + dz * dz > 0.5 * 0.5)
            return false;

        getPlayer().setPos(x, y, z);

        if (sync)
            getPlayer().networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionOnly(x, y, z, getPlayer().onGround));

        return true;
    }

    public boolean moveTo(double x, double z) {
        return moveTo(x, z, true);
    }

    public boolean moveTo(double x, double z, boolean smart) {
        if (getPlayer().squaredDistanceTo(x, getY(), z) < 0.01) {
            snapTo(x, getY(), z);
            return true;
        }

        lookAt(x, getY() + getEyeHeight(), z);
        boolean wasBlockingInput = ScriptManager.isCurrentScriptBlockingInput();
        ScriptManager.blockInput(true);
        boolean wasPressingForward = ScriptManager.getScriptInput().pressingForward;
        ScriptManager.getScriptInput().pressingForward = true;

        double lastDistanceSq = getPlayer().squaredDistanceTo(x, getY(), z);
        int tickCounter = 0;
        boolean successful = true;

        do {
            if (smart) {
                double dx = x - getX();
                double dz = z - getZ();
                double n = Math.sqrt(dx * dx + dz * dz);
                dx /= n;
                dz /= n;
                BlockPos pos = new BlockPos(MathHelper.floor(getX() + dx), MathHelper.floor(getY()), MathHelper.floor(getZ() + dz));
                World world = getPlayer().world;
                if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                        && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
                        && world.getBlockState(pos.up(2)).getCollisionShape(world, pos.up(2)).isEmpty()) {
                    BlockPos aboveHead = new BlockPos(getPlayer()).up(2);
                    if (world.getBlockState(aboveHead).getCollisionShape(world, aboveHead).isEmpty()) {
                        if (getPlayer().squaredDistanceTo(x, getY(), z) > 1
                                || getPlayer().getBoundingBox().offset(x - getX(), 0, z - getZ()).intersects(new Box(pos))) {
                            boolean wasJumping = ScriptManager.getScriptInput().jumping;
                            ScriptManager.getScriptInput().jumping = true;
                            ScriptManager.passTick();
                            ScriptManager.getScriptInput().jumping = wasJumping;
                        }
                    }
                }
            }
            lookAt(x, getY() + getEyeHeight(), z);
            ScriptManager.passTick();

            tickCounter++;
            if (tickCounter % 20 == 0) {
                double distanceSq = getPlayer().squaredDistanceTo(x, getY(), z);
                if (distanceSq >= lastDistanceSq) {
                    successful = false;
                    break;
                }
                lastDistanceSq = distanceSq;
            }
        } while (getPlayer().squaredDistanceTo(x, getY(), z) > 0.25 * 0.25);
        snapTo(x, getY(), z);

        ScriptManager.getScriptInput().pressingForward = wasPressingForward;
        ScriptManager.blockInput(wasBlockingInput);

        return successful;
    }

    public boolean pathTo(double x, double y, double z) {
        return pathTo(x, y, z, null);
    }

    public boolean pathTo(double x, double y, double z, JSObject hints) {
        BlockPos pos = new BlockPos(x, y, z);
        return pathTo0(() -> pos, hints, false);
    }

    public boolean pathTo(Object thing) {
        return pathTo(thing, null);
    }

    public boolean pathTo(Object thing, JSObject hints) {
        if (thing instanceof ScriptEntity) {
            Entity entity = ((ScriptEntity) thing).getEntity();
            return pathTo0(() -> new BlockPos(entity), hints, true);
        } else {
            JSObject func = ScriptUtil.asFunction(thing);
            return pathTo0(() -> {
                JSObject posObj = ScriptUtil.asObject(func.call(null));
                double x = ScriptUtil.asNumber(posObj.getMember("x")).doubleValue();
                double y = ScriptUtil.asNumber(posObj.getMember("y")).doubleValue();
                double z = ScriptUtil.asNumber(posObj.getMember("z")).doubleValue();
                return new BlockPos(x, y, z);
            }, hints, true);
        }
    }

    private boolean pathTo0(Supplier<BlockPos> target, JSObject hints, boolean movingTarget) {
        JSObject nodeTypeFunction = hints != null && hints.hasMember("nodeTypeFunction") ? ScriptUtil.asFunction(hints.getMember("nodeTypeFunction")) : null;
        JSObject penaltyFunction = hints != null && hints.hasMember("penaltyFunction") ? ScriptUtil.asFunction(hints.getMember("penaltyFunction")) : null;
        Float followRange = hints != null && hints.hasMember("followRange") ? ScriptUtil.asNumber(hints.getMember("followRange")).floatValue() : null;
        int reachDistance = hints != null && hints.hasMember("reachDistance") ? ScriptUtil.asNumber(hints.getMember("reachDistance")).intValue() : 0;
        Float maxPathLength = hints != null && hints.hasMember("maxPathLength") ? ScriptUtil.asNumber(hints.getMember("maxPathLength")).floatValue() : null;

        BlockPos[] targetPos = {target.get()};

        PathfindingHints javaHints = new PathfindingHints() {
            @Override
            public PathNodeType getNodeType(BlockView world, BlockPos pos) {
                if (nodeTypeFunction == null)
                    return null;

                Object typeObj = nodeTypeFunction.call(null, pos.getX(), pos.getY(), pos.getZ());
                if (typeObj == null)
                    return null;

                String typeName = ScriptUtil.asString(typeObj);
                try {
                    return PathNodeType.valueOf(typeName.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown path node type \"" + typeName + "\"");
                }
            }

            @Override
            public float getPathfindingPenalty(PathNodeType type) {
                if (penaltyFunction == null)
                    return type.getDefaultPenalty();

                String typeName = type.name().toLowerCase(Locale.ROOT);
                Object penaltyObj = penaltyFunction.call(null, typeName);

                if (penaltyObj == null)
                    return type.getDefaultPenalty();

                return ScriptUtil.asNumber(penaltyObj).floatValue();
            }

            @Override
            public float getFollowRange() {
                if (followRange != null)
                    return followRange;
                return (float) Math.sqrt(getPlayer().squaredDistanceTo(targetPos[0].getX() + 0.5, targetPos[0].getY() + 0.5, targetPos[0].getZ() + 0.5)) * 2;
            }

            @Override
            public int getReachDistance() {
                return reachDistance;
            }

            @Override
            public float getMaxPathLength() {
                if (maxPathLength != null)
                    return maxPathLength;
                return (float) Math.sqrt(getPlayer().squaredDistanceTo(targetPos[0].getX() + 0.5, targetPos[0].getY() + 0.5, targetPos[0].getZ() + 0.5)) * 2;
            }
        };

        Path[] path = {PlayerPathfinder.findPathToAny(ImmutableSet.of(targetPos[0]), javaHints)};
        boolean[] needsRecalc = {false};
        //noinspection Convert2Lambda - need a new instance every time
        IBlockChangeListener blockChangeListener = new IBlockChangeListener() {
            @Override
            public void onBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
                if (path[0] == null || path[0].isFinished() || path[0].getLength() == 0)
                    return;
                PathNode end = path[0].getEnd();
                Vec3d halfway = new Vec3d((end.x + getPlayer().getX()) / 2, (end.y + getPlayer().getY()) / 2, (end.z + getPlayer().getZ()) / 2);
                if (pos.isWithinDistance(halfway, path[0].getLength() - path[0].getCurrentNodeIndex())) {
                    needsRecalc[0] = true;
                }
            }
        };
        IBlockChangeListener.LISTENERS.add(blockChangeListener);

        try {
            while (path[0] != null && !path[0].isFinished()) {
                Vec3d currentPosition = path[0].getCurrentPosition();
                if (!moveTo(currentPosition.x + 0.5, currentPosition.z + 0.5))
                    return false;
                path[0].setCurrentNodeIndex(path[0].getCurrentNodeIndex() + 1);
                if (movingTarget || needsRecalc[0]) {
                    BlockPos lastTargetPos = target.get();
                    targetPos[0] = target.get();
                    if (!lastTargetPos.equals(targetPos[0]) || needsRecalc[0]) {
                        needsRecalc[0] = false;
                        path[0] = PlayerPathfinder.findPathToAny(ImmutableSet.of(targetPos[0]), javaHints);
                    }
                }
            }
        } finally {
            IBlockChangeListener.LISTENERS.remove(blockChangeListener);
        }

        return path[0] != null;
    }

    public void setYaw(float yaw) {
        getPlayer().yaw = yaw;
    }

    public void setPitch(float pitch) {
        getPlayer().pitch = pitch;
    }

    public void lookAt(double x, double y, double z) {
        ClientPlayerEntity player = getPlayer();
        double dx = x - player.getX();
        double dy = y - (player.getY() + getEyeHeight());
        double dz = z - player.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        player.yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        player.pitch = (float) -Math.toDegrees(Math.atan2(dy, dh));
    }

    public void lookAt(ScriptEntity entity) {
        if (entity instanceof ScriptLivingEntity) {
            double eyeHeight = ((ScriptLivingEntity) entity).getEyeHeight();
            lookAt(entity.getX(), entity.getY() + eyeHeight, entity.getZ());
        } else {
            lookAt(entity.getX(), entity.getY(), entity.getZ());
        }
    }

    public void syncRotation() {
        getPlayer().networkHandler.sendPacket(new PlayerMoveC2SPacket.LookOnly(getPlayer().yaw, getPlayer().pitch, getPlayer().onGround));
    }

    public int getSelectedSlot() {
        return getPlayer().inventory.selectedSlot;
    }

    public void setSelectedSlot(int slot) {
        getPlayer().inventory.selectedSlot = MathHelper.clamp(slot, 0, 8);
    }

    public ScriptInventory getInventory() {
        return new ScriptInventory(getPlayer().playerContainer);
    }

    public ScriptInventory getOpenContainer() {
        Container container = getPlayer().container;
        if (container == getPlayer().playerContainer)
            return null;
        else
            return new ScriptInventory(getPlayer().container);
    }

    public void closeContainer() {
        if (getPlayer().container != getPlayer().playerContainer)
            getPlayer().closeContainer();
    }

    public boolean pick(Object itemStack) {
        Predicate<ItemStack> predicate;
        if (itemStack instanceof String) {
            Item item = Registry.ITEM.get(new Identifier((String) itemStack));
            predicate = stack -> stack.getItem() == item;
        } else if (ScriptUtil.isFunction(itemStack)) {
            JSObject jsObject = ScriptUtil.asFunction(itemStack);
            predicate = stack -> {
                Object result = jsObject.call(null, ScriptUtil.fromNbt(stack.toTag(new CompoundTag())));
                return ScriptUtil.asBoolean(result);
            };
        } else {
            Tag nbt = ScriptUtil.toNbt(itemStack);
            if (!(nbt instanceof CompoundTag))
                throw new IllegalArgumentException(itemStack.toString());
            predicate = stack -> NbtHelper.matches(nbt, stack.toTag(new CompoundTag()), true);
        }

        PlayerInventory inv = getPlayer().inventory;
        int slot;
        for (slot = 0; slot < inv.main.size(); slot++) {
            if (predicate.test(inv.main.get(slot)))
                break;
        }
        if (slot == inv.main.size())
            return false;

        if (PlayerInventory.isValidHotbarIndex(slot)) {
            setSelectedSlot(slot);
        } else {
            int hotbarSlot = getSelectedSlot();
            do {
                if (inv.main.get(hotbarSlot).isEmpty())
                    break;
                hotbarSlot = (hotbarSlot + 1) % 9;
            } while (hotbarSlot != getSelectedSlot());
            setSelectedSlot(hotbarSlot);
            MinecraftClient.getInstance().interactionManager.pickFromInventory(slot);
        }
        return true;
    }

    public boolean rightClick() {
        for (Hand hand : Hand.values()) {
            ActionResult result = MinecraftClient.getInstance().interactionManager.interactItem(getPlayer(), getPlayer().world, hand);
            if (result == ActionResult.SUCCESS) {
                MinecraftClient.getInstance().gameRenderer.firstPersonRenderer.resetEquipProgress(hand);
                return true;
            }
            if (result == ActionResult.FAIL)
                return false;
        }
        return false;
    }

    public boolean leftClick(int x, int y, int z) {
        return leftClick(x, y, z, null);
    }

    public boolean leftClick(int x, int y, int z, String side) {
        Vec3d closestPos = ScriptWorld.getClosestVisiblePoint0(x, y, z, side, true);
        if (closestPos == null)
            return false;
        lookAt(closestPos.x, closestPos.y, closestPos.z);
        getPlayer().swingHand(Hand.MAIN_HAND);
        Vec3d origin = getPlayer().getCameraPosVec(0);
        Direction dir = ScriptUtil.getDirectionFromString(side);
        return MinecraftClient.getInstance().interactionManager.attackBlock(new BlockPos(x, y, z),
                dir == null ? Direction.getFacing((float) (closestPos.x - origin.x), (float) (closestPos.y - origin.y), (float) (closestPos.z - origin.z)) : dir);
    }

    public boolean rightClick(int x, int y, int z) {
        return rightClick(x, y, z, null);
    }

    public boolean rightClick(int x, int y, int z, String side) {
        Vec3d closestPos = ScriptWorld.getClosestVisiblePoint0(x, y, z, side, true);
        if (closestPos == null)
            return false;
        lookAt(closestPos.x, closestPos.y, closestPos.z);
        Vec3d origin = getPlayer().getCameraPosVec(0);
        Direction dir = ScriptUtil.getDirectionFromString(side);
        for (Hand hand : Hand.values()) {
            ActionResult result = MinecraftClient.getInstance().interactionManager.interactBlock(getPlayer(), MinecraftClient.getInstance().world, hand,
                    new BlockHitResult(closestPos,
                            dir == null ? Direction.getFacing((float) (closestPos.x - origin.x), (float) (closestPos.y - origin.y), (float) (closestPos.z - origin.z)) : dir,
                            new BlockPos(x, y, z), false));
            if (result == ActionResult.SUCCESS) {
                getPlayer().swingHand(hand);
                return true;
            }
            if (result == ActionResult.FAIL)
                return false;
        }
        return false;
    }

    public boolean leftClick(ScriptEntity entity) {
        if (getPlayer().squaredDistanceTo(entity.getEntity()) > 6 * 6)
            return false;

        lookAt(entity);
        getPlayer().swingHand(Hand.MAIN_HAND);
        MinecraftClient.getInstance().interactionManager.attackEntity(getPlayer(), entity.getEntity());
        return true;
    }

    public boolean rightClick(ScriptEntity entity) {
        if (getPlayer().squaredDistanceTo(entity.getEntity()) > 6 * 6)
            return false;

        for (Hand hand : Hand.values()) {
            ActionResult result = MinecraftClient.getInstance().interactionManager.interactEntity(getPlayer(), entity.getEntity(), hand);
            if (result == ActionResult.SUCCESS) {
                lookAt(entity);
                return true;
            }
            if (result == ActionResult.FAIL)
                return false;
        }
        return false;
    }

    public void blockInput() {
        ScriptManager.blockInput(true);
    }

    public void unblockInput() {
        ScriptManager.blockInput(false);
    }

    public boolean longUseItem() {
        if (!rightClick())
            return false;
        if (!getPlayer().isUsingItem())
            return false;
        boolean wasBlockingInput = ScriptManager.isCurrentScriptBlockingInput();
        ScriptManager.blockInput(true);
        do {
            ScriptManager.passTick();
        } while (getPlayer().isUsingItem());
        ScriptManager.blockInput(wasBlockingInput);
        return true;
    }

    public boolean longMineBlock(int x, int y, int z) {
        if (!leftClick(x, y, z))
            return false;
        ClientPlayerInteractionManager interactionManager = MinecraftClient.getInstance().interactionManager;
        if (!interactionManager.isBreakingBlock())
            return false;
        boolean wasBlockingInput = ScriptManager.isCurrentScriptBlockingInput();
        boolean successful = true;
        ScriptManager.blockInput(true);
        BlockPos pos = new BlockPos(x, y, z);
        do {
            HitResult hitResult = MinecraftClient.getInstance().crosshairTarget;
            if (hitResult.getType() != HitResult.Type.BLOCK || !((BlockHitResult) hitResult).getBlockPos().equals(pos)) {
                Vec3d closestPos = MathUtil.getClosestVisiblePoint(MinecraftClient.getInstance().world, pos, getPlayer().getCameraPosVec(0), getPlayer());
                if (closestPos == null) {
                    successful = false;
                    break;
                }
                lookAt(closestPos.x, closestPos.y, closestPos.z);
            }
            IMinecraftClient imc = (IMinecraftClient) MinecraftClient.getInstance();
            imc.resetAttackCooldown();
            imc.continueBreakingBlock();
            ScriptManager.passTick();
        } while (interactionManager.isBreakingBlock());
        ScriptManager.blockInput(wasBlockingInput);
        return successful;
    }

    public void setPressingForward(boolean pressingForward) {
        ScriptManager.getScriptInput().pressingForward = pressingForward;
    }

    public boolean isPressingForward() {
        return ScriptManager.getScriptInput().pressingForward || getPlayer().input.pressingForward;
    }

    public void setPressingBack(boolean pressingBack) {
        ScriptManager.getScriptInput().pressingBack = pressingBack;
    }

    public boolean isPressingBack() {
        return ScriptManager.getScriptInput().pressingBack || getPlayer().input.pressingBack;
    }

    public void setPressingLeft(boolean pressingLeft) {
        ScriptManager.getScriptInput().pressingLeft = pressingLeft;
    }

    public boolean isPressingLeft() {
        return ScriptManager.getScriptInput().pressingLeft || getPlayer().input.pressingLeft;
    }

    public void setPressingRight(boolean pressingRight) {
        ScriptManager.getScriptInput().pressingRight = pressingRight;
    }

    public boolean isPressingRight() {
        return ScriptManager.getScriptInput().pressingRight || getPlayer().input.pressingRight;
    }

    public void setJumping(boolean jumping) {
        ScriptManager.getScriptInput().jumping = jumping;
    }

    public boolean isJumping() {
        return ScriptManager.getScriptInput().jumping || getPlayer().input.jumping;
    }

    public void setSneaking(boolean sneaking) {
        ScriptManager.getScriptInput().sneaking = sneaking;
    }

    public boolean isSneaking() {
        return ScriptManager.getScriptInput().sneaking || getPlayer().input.sneaking;
    }

    public void setSprinting(boolean sprinting) {
        ScriptManager.setSprinting(sprinting);
    }

    public boolean isSprinting() {
        return ScriptManager.isCurrentThreadSprinting() || MinecraftClient.getInstance().options.keySprint.isPressed();
    }

}
