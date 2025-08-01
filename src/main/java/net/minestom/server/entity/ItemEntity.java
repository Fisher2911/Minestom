package net.minestom.server.entity;

import net.minestom.server.entity.metadata.item.ItemEntityMeta;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.entity.EntityItemMergeEvent;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.item.ItemStack;
import net.minestom.server.thread.Acquirable;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.utils.time.Cooldown;
import net.minestom.server.utils.time.TimeUnit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.temporal.TemporalUnit;

/**
 * Represents an item on the ground.
 */
public class ItemEntity extends Entity {

    /**
     * Used to slow down the merge check delay
     */
    private static Duration mergeDelay = Duration.of(10, TimeUnit.SERVER_TICK);

    /**
     * The last time that this item has checked his neighbors for merge
     */
    private long lastMergeCheck;

    private ItemStack itemStack;

    private boolean pickable = true;
    private boolean mergeable = true;
    private float mergeRange = 1;
    private boolean previousOnGround = false;

    // Spawn time in System#nanoTime
    private long spawnTime;
    // pickup delay in nanos
    private long pickupDelay;

    public ItemEntity(@NotNull ItemStack itemStack) {
        super(EntityType.ITEM);
        setItemStack(itemStack);
        setBoundingBox(0.25f, 0.25f, 0.25f);
    }

    /**
     * Gets the update option for the merging feature.
     *
     * @return the merge update option
     */
    @Nullable
    public static Duration getMergeDelay() {
        return mergeDelay;
    }

    /**
     * Changes the merge delay.
     * Can be set to null to entirely remove the delay.
     *
     * @param delay the new merge delay
     */
    public static void setMergeDelay(@Nullable Duration delay) {
        ItemEntity.mergeDelay = delay;
    }

    @Override
    public void update(long time) {
        if (isMergeable() && isPickable() &&
                (mergeDelay == null || !Cooldown.hasCooldown(time, lastMergeCheck, mergeDelay))) {
            this.lastMergeCheck = time;

            this.instance.getEntityTracker().nearbyEntities(position, mergeRange,
                    EntityTracker.Target.ITEMS, itemEntity -> {
                        if (itemEntity == this) return;
                        if (!itemEntity.isPickable() || !itemEntity.isMergeable()) return;

                        final ItemStack itemStackEntity = itemEntity.getItemStack();
                        final boolean canStack = itemStack.isSimilar(itemStackEntity);

                        if (!canStack) return;
                        final int totalAmount = itemStack.amount() + itemStackEntity.amount();
                        if (!MathUtils.isBetween(totalAmount, 0, itemStack.maxStackSize())) return;
                        final ItemStack result = itemStack.withAmount(totalAmount);
                        EntityItemMergeEvent entityItemMergeEvent = new EntityItemMergeEvent(this, itemEntity, result);
                        EventDispatcher.callCancellable(entityItemMergeEvent, () -> {
                            setItemStack(entityItemMergeEvent.getResult());
                            itemEntity.remove();
                        });
                    });
        }
    }

    @Override
    public void movementTick() {
        super.movementTick();

        if (!previousOnGround && onGround) {
            synchronizePosition();
            sendPacketToViewers(getVelocityPacket());
        }

        previousOnGround = onGround;
    }

    @Override
    public void spawn() {
        this.spawnTime = System.nanoTime();
    }

    @Override
    public @NotNull ItemEntityMeta getEntityMeta() {
        return (ItemEntityMeta) super.getEntityMeta();
    }

    /**
     * Gets the item stack on ground.
     *
     * @return the item stack
     */
    @NotNull
    public ItemStack getItemStack() {
        return itemStack;
    }

    /**
     * Changes the item stack on ground.
     *
     * @param itemStack the item stack
     */
    public void setItemStack(@NotNull ItemStack itemStack) {
        this.itemStack = itemStack;
        getEntityMeta().setItem(itemStack);
    }

    /**
     * Gets if the item is currently pickable.
     * <p>
     * {@link #setPickable(boolean)} needs to be true and the delay {@link #getPickupDelay()}
     * to be long gone.
     *
     * @return true if the item is pickable, false otherwise
     */
    public boolean isPickable() {
        return pickable && getTimeSinceSpawn() >= pickupDelay;
    }

    /**
     * Makes the item pickable.
     *
     * @param pickable true to make the item pickable, false otherwise
     */
    public void setPickable(boolean pickable) {
        this.pickable = pickable;
    }

    /**
     * Gets if the item is mergeable.
     *
     * @return true if the entity is mergeable, false otherwise
     */
    public boolean isMergeable() {
        return mergeable;
    }

    /**
     * When set to true, close {@link ItemEntity} will try to merge together as a single entity
     * when their {@link #getItemStack()} is similar and allowed to stack together.
     *
     * @param mergeable should the entity merge with other {@link ItemEntity}
     */
    public void setMergeable(boolean mergeable) {
        this.mergeable = mergeable;
    }

    /**
     * Gets the merge range.
     *
     * @return the merge range
     */
    public float getMergeRange() {
        return mergeRange;
    }

    /**
     * Changes the merge range.
     *
     * @param mergeRange the merge range
     */
    public void setMergeRange(float mergeRange) {
        this.mergeRange = mergeRange;
    }

    /**
     * Gets the pickup delay in milliseconds, defined by {@link #setPickupDelay(Duration)}.
     *
     * @return the pickup delay
     */
    public long getPickupDelay() {
        return pickupDelay;
    }

    /**
     * Sets the pickup delay of the ItemEntity.
     *
     * @param delay        the pickup delay
     * @param temporalUnit the unit of the delay
     */
    public void setPickupDelay(long delay, @NotNull TemporalUnit temporalUnit) {
        setPickupDelay(Duration.of(delay, temporalUnit));
    }

    /**
     * Sets the pickup delay of the ItemEntity.
     *
     * @param delay the pickup delay
     */
    public void setPickupDelay(Duration delay) {
        this.pickupDelay = delay.toMillis();
    }

    /**
     * Used to know if the ItemEntity can be picked up.
     *
     * @return the elapsed time in milliseconds since this entity has spawned
     * @deprecated use {@link #getTimeSinceSpawn()} instead, does the same thing with better naming
     */
    @Deprecated(forRemoval = true)
    public long getSpawnTime() {
        return getTimeSinceSpawn();
    }
    
    /**
     * Used to know if the ItemEntity can be picked up.
     *
     * @return the elapsed time in milliseconds since this entity has spawned
     */
    public long getTimeSinceSpawn() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - spawnTime);
    }

    @ApiStatus.Experimental
    @SuppressWarnings("unchecked")
    @Override
    public @NotNull Acquirable<? extends ItemEntity> acquirable() {
        return (Acquirable<? extends ItemEntity>) super.acquirable();
    }
}
