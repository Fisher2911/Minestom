package net.minestom.server.item.enchant;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponentMap;
import net.minestom.server.entity.EquipmentSlotGroup;
import net.minestom.server.item.Material;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.registry.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public sealed interface Enchantment extends Enchantments permits EnchantmentImpl {
    @NotNull NetworkBuffer.Type<RegistryKey<Enchantment>> NETWORK_TYPE = RegistryKey.networkType(Registries::enchantment);
    @NotNull Codec<RegistryKey<Enchantment>> CODEC = RegistryKey.codec(Registries::enchantment);

    @NotNull Codec<Enchantment> REGISTRY_CODEC = StructCodec.struct(
            "description", Codec.COMPONENT, Enchantment::description,
            "exclusive_set", RegistryTag.codec(Registries::enchantment).optional(RegistryTag.empty()), Enchantment::exclusiveSet,
            "supported_items", RegistryTag.codec(Registries::material), Enchantment::supportedItems,
            "primary_items", RegistryTag.codec(Registries::material).optional(), Enchantment::primaryItems,
            "weight", Codec.INT, Enchantment::weight,
            "max_level", Codec.INT, Enchantment::maxLevel,
            "min_cost", Cost.CODEC, Enchantment::minCost,
            "max_cost", Cost.CODEC, Enchantment::maxCost,
            "anvil_cost", Codec.INT, Enchantment::anvilCost,
            "slots", EquipmentSlotGroup.CODEC.list(), Enchantment::slots,
            "effects", EffectComponent.CODEC.optional(DataComponentMap.EMPTY), Enchantment::effects,
            EnchantmentImpl::new);

    static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * <p>Creates a new registry for enchantments, loading the vanilla enchantments.</p>
     *
     * @see net.minestom.server.MinecraftServer to get an existing instance of the registry
     */
    @ApiStatus.Internal
    static @NotNull DynamicRegistry<Enchantment> createDefaultRegistry(@NotNull Registries registries) {
        return DynamicRegistry.createForEnchantmentsWithSelfReferentialLoadingNightmare(
                Key.key("enchantment"), REGISTRY_CODEC, RegistryData.Resource.ENCHANTMENTS, registries
        );
    }

    @NotNull Component description();

    @NotNull RegistryTag<Enchantment> exclusiveSet();

    @NotNull RegistryTag<Material> supportedItems();

    @Nullable RegistryTag<Material> primaryItems();

    int weight();

    int maxLevel();

    @NotNull Cost minCost();

    @NotNull Cost maxCost();

    int anvilCost();

    @NotNull List<EquipmentSlotGroup> slots();

    @NotNull DataComponentMap effects();

    enum Target {
        ATTACKER,
        DAMAGING_ENTITY,
        VICTIM;

        public static final Codec<Target> CODEC = Codec.Enum(Target.class);
    }

    sealed interface Effect permits AttributeEffect, ConditionalEffect, DamageImmunityEffect, EntityEffect, LocationEffect, TargetedConditionalEffect, ValueEffect {

    }

    record Cost(int base, int perLevelAboveFirst) {
        public static final Cost DEFAULT = new Cost(1, 1);

        public static final Codec<Cost> CODEC = StructCodec.struct(
                "base", Codec.INT, Cost::base,
                "per_level_above_first", Codec.INT, Cost::perLevelAboveFirst,
                Cost::new);
    }

    class Builder {
        private Component description = Component.empty();
        private RegistryTag<Enchantment> exclusiveSet = RegistryTag.empty();
        private RegistryTag<Material> supportedItems = RegistryTag.empty();
        private RegistryTag<Material> primaryItems = RegistryTag.empty();
        private int weight = 1;
        private int maxLevel = 1;
        private Cost minCost = Cost.DEFAULT;
        private Cost maxCost = Cost.DEFAULT;
        private int anvilCost = 0;
        private List<EquipmentSlotGroup> slots = List.of();
        private DataComponentMap.Builder effects = DataComponentMap.builder();

        private Builder() {
        }

        public @NotNull Builder description(@NotNull Component description) {
            this.description = description;
            return this;
        }

        public @NotNull Builder exclusiveSet(@NotNull RegistryTag<Enchantment> exclusiveSet) {
            this.exclusiveSet = exclusiveSet;
            return this;
        }

        public @NotNull Builder supportedItems(@NotNull RegistryTag<Material> supportedItems) {
            this.supportedItems = supportedItems;
            return this;
        }

        public @NotNull Builder primaryItems(@NotNull RegistryTag<Material> primaryItems) {
            this.primaryItems = primaryItems;
            return this;
        }

        public @NotNull Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        public @NotNull Builder maxLevel(int maxLevel) {
            this.maxLevel = maxLevel;
            return this;
        }

        public @NotNull Builder minCost(int base, int perLevelAboveFirst) {
            return minCost(new Cost(base, perLevelAboveFirst));
        }

        public @NotNull Builder minCost(@NotNull Cost minCost) {
            this.minCost = minCost;
            return this;
        }

        public @NotNull Builder maxCost(int base, int perLevelAboveFirst) {
            return maxCost(new Cost(base, perLevelAboveFirst));
        }

        public @NotNull Builder maxCost(@NotNull Cost maxCost) {
            this.maxCost = maxCost;
            return this;
        }

        public @NotNull Builder anvilCost(int anvilCost) {
            this.anvilCost = anvilCost;
            return this;
        }

        public @NotNull Builder slots(@NotNull EquipmentSlotGroup... slots) {
            this.slots = List.of(slots);
            return this;
        }

        public @NotNull Builder slots(@NotNull List<EquipmentSlotGroup> slots) {
            this.slots = slots;
            return this;
        }

        public <T> @NotNull Builder effect(@NotNull DataComponent<T> component, @NotNull T value) {
            effects.set(component, value);
            return this;
        }

        public @NotNull Builder effects(@NotNull DataComponentMap effects) {
            this.effects = effects.toBuilder();
            return this;
        }

        public @NotNull Enchantment build() {
            return new EnchantmentImpl(
                    description, exclusiveSet, supportedItems,
                    primaryItems, weight, maxLevel, minCost, maxCost,
                    anvilCost, slots, effects.build()
            );
        }
    }

}
