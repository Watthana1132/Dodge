package com.rushberry.dodgesword;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class DodgeSwordItem extends Item {

    // === ตั้งค่าระบบชาร์จเกจ (Reload Bar) ===
    private static final int MAX_CHARGE_TICKS = 35; 
    private static final int CHARGE_BAR_COLOR_EMPTY = 0x2A0042;
    private static final int CHARGE_BAR_COLOR_FULL = 0xCC00FA; 
    private static final Map<Integer, Integer> LIVE_CHARGE_BAR_TICKS = Collections.synchronizedMap(new HashMap<>());

    private static final double BASE_DAMAGE = 8.0; 
    // หมายเหตุ: ถ้าอยากให้ความเร็วในการฟันเท่าดาบปกติ (1.6) ให้เปลี่ยนตรงนี้เป็น -2.4 ครับ (เพราะมือเปล่าคือ 4.0 ลบ 2.4 = 1.6)
    private static final double BASE_ATTACK_SPEED = -2.4; 
    private static final Identifier DODGE_ATTACK_DAMAGE_ID = Identifier.fromNamespaceAndPath("dodgesword", "dodge_attack_damage");
    private static final Identifier DODGE_ATTACK_SPEED_ID = Identifier.fromNamespaceAndPath("dodgesword", "dodge_attack_speed");

    // --- แก้ไข Constructor: บังคับยัดสเตตัสเริ่มต้นตั้งแต่ตอนสร้างไอเทม ---
    public DodgeSwordItem(Item.Properties properties) {
        super(properties.component(DataComponents.ATTRIBUTE_MODIFIERS, createBaseAttributes()));
    }

    // ฟังก์ชันสร้างสเตตัสเริ่มต้นสำหรับโชว์ในหน้า Creative
    private static ItemAttributeModifiers createBaseAttributes() {
        return ItemAttributeModifiers.builder()
                .add(
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(DODGE_ATTACK_DAMAGE_ID, BASE_DAMAGE - 1.0, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(
                        Attributes.ATTACK_SPEED,
                        new AttributeModifier(DODGE_ATTACK_SPEED_ID, BASE_ATTACK_SPEED, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    // ==========================================
    // ระบบเก็บข้อมูล Kill Count, Bonus & UUID (Shambles)
    // ==========================================
    public static int getKillCount(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.copyTag().getInt("DodgeSwordKills").orElse(0);
    }

    public static void addKill(ItemStack stack) {
        int current = getKillCount(stack);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt("DodgeSwordKills", current + 1));
        syncDodgeSwordAttributes(stack);
    }

    public static float getBonusDamage(ItemStack stack) {
        return Math.min(getKillCount(stack) * 0.20F, 50.0F);
    }

    public static double getBonusRange(ItemStack stack) {
        return Math.min(getKillCount(stack) * 0.15D, 18.0D);
    }

    // --- แก้ไข: แกะกล่อง Optional ให้เป็น String ปกติ ---
    private static UUID getMark1(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.copyTag().contains("Mark1")) {
            try { return UUID.fromString(data.copyTag().getString("Mark1").orElse("")); } catch (Exception e) { return null; }
        }
        return null;
    }

    private static UUID getMark2(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.copyTag().contains("Mark2")) {
            try { return UUID.fromString(data.copyTag().getString("Mark2").orElse("")); } catch (Exception e) { return null; }
        }
        return null;
    }
    
    private static void setMark1(ItemStack stack, UUID uuid) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("Mark1", uuid.toString()));
    }

    private static void setMark2(ItemStack stack, UUID uuid) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("Mark2", uuid.toString()));
    }

    private static void clearMarks(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove("Mark1");
            tag.remove("Mark2");
        });
    }

    public static void syncDodgeSwordAttributes(ItemStack stack) {
        float bonusDamage = getBonusDamage(stack);
        double totalDamage = BASE_DAMAGE + bonusDamage;
        double itemDamageModifierAmount = totalDamage - 1.0;

        ItemAttributeModifiers modifiers = ItemAttributeModifiers.builder()
                .add(
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(DODGE_ATTACK_DAMAGE_ID, itemDamageModifierAmount, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(
                        Attributes.ATTACK_SPEED,
                        new AttributeModifier(DODGE_ATTACK_SPEED_ID, BASE_ATTACK_SPEED, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);

        if (!stack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            syncDodgeSwordAttributes(stack);
        
            if (entity instanceof LivingEntity living) {
            if (!living.isUsingItem() || living.getUseItem() != stack) {
                clearLiveChargeTicks(stack);
            }
        }




        }

        if (entity instanceof LivingEntity living) {
            if (!living.isUsingItem() || living.getUseItem() != stack) {
                clearLiveChargeTicks(stack);
            }
        }
    }

   
    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.WITHER, 40, 1, false, true, true));
        
        attacker.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false, true));
        attacker.addEffect(new MobEffectInstance(MobEffects.SATURATION, 60, 0, false, false, true));
        
        if (attacker instanceof Player player) {
            Level level = player.level();
            
            if (!level.isClientSide()) {
                Vec3 look = player.getLookAngle();
                ((ServerLevel) level).sendParticles(ParticleTypes.SWEEP_ATTACK, 
                        player.getX() + look.x, player.getY(0.5D), player.getZ() + look.z, 
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                
                
                UUID targetUUID = target.getUUID();
                UUID mark1 = getMark1(stack);
                UUID mark2 = getMark2(stack);

                if (!targetUUID.equals(mark1) && !targetUUID.equals(mark2)) {
                    if (mark1 == null) {
                        setMark1(stack, targetUUID);
                        player.sendSystemMessage(Component.literal("Marked Mob 1!").withStyle(ChatFormatting.GREEN));
                    } else if (mark2 == null) {
                        setMark2(stack, targetUUID);
                        player.sendSystemMessage(Component.literal("Marked Mob 2!").withStyle(ChatFormatting.GREEN));
                    } else {
                        setMark1(stack, mark2);
                        setMark2(stack, targetUUID);
                        player.sendSystemMessage(Component.literal("Marks Updated!").withStyle(ChatFormatting.YELLOW));
                    }
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, true));
                    level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.5F);
                }
            }
        }

        super.hurtEnemy(stack, target, attacker);
        
        // --- ระบบซ่อมดาบเมื่อฟันศัตรูตาย ---
        if (target.isDeadOrDying() || target.getHealth() <= 0.0F) {
            if (stack.isDamageableItem()) {
                stack.setDamageValue(Math.max(0, stack.getDamageValue() - 4)); 
                
                if (!attacker.level().isClientSide()) {
                    ((ServerLevel) attacker.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, attacker.getX(), attacker.getY() + 1.0, attacker.getZ(), 5, 0.3D, 0.3D, 0.3D, 0.1D);
                    attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.2F);
                }
            }
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true; 
    }

    // ==========================================
    // 2. ฟังก์ชันเมื่อกดคลิกขวา (Shambles Swap & Charge)
    // ==========================================
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!stack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            syncDodgeSwordAttributes(stack);
        }

        if (!level.isClientSide()) {
            
            // --- ระบบ Shambles สลับที่เป้าหมายด้วย Shift + คลิกขวา ---
            if (player.isShiftKeyDown()) {
                double range = 32.0D;
                Vec3 start = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                Vec3 end = start.add(look.scale(range)); 
                AABB searchBox = player.getBoundingBox().expandTowards(look.scale(range)).inflate(2.0D);

                LivingEntity target = null;
                double closestDist = range * range;

                // หาระยะเล็งไปที่มอนสเตอร์
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, searchBox, entity -> entity != player && entity.isAlive())) {
                    AABB targetBox = e.getBoundingBox().inflate(0.5D); 
                    Optional<Vec3> hitOpt = targetBox.clip(start, end); 
                    
                    if (hitOpt.isPresent()) {
                        double dist = start.distanceToSqr(hitOpt.get());
                        if (dist < closestDist) {
                            closestDist = dist;
                            target = e;
                        }
                    }
                }

                if (target != null) {
                    UUID u = target.getUUID();
                    UUID m1 = getMark1(stack);
                    UUID m2 = getMark2(stack);
                    
                    if (m1 != null && m2 != null && (u.equals(m1) || u.equals(m2))) {
                        Entity e1 = ((ServerLevel) level).getEntity(m1);
                        Entity e2 = ((ServerLevel) level).getEntity(m2);
                        
                        if (e1 instanceof LivingEntity le1 && e2 instanceof LivingEntity le2 && le1.isAlive() && le2.isAlive()) {
                            Vec3 pos1 = le1.position();
                            Vec3 pos2 = le2.position();
                            
                            // สลับที่!
                            le1.teleportTo(pos2.x, pos2.y, pos2.z);
                            le2.teleportTo(pos1.x, pos1.y, pos1.z);
                            
                            // เอฟเฟกต์
                            ((ServerLevel) level).sendParticles(ParticleTypes.PORTAL, pos1.x, pos1.y + 1.0, pos1.z, 30, 0.5, 0.5, 0.5, 0.1);
                            ((ServerLevel) level).sendParticles(ParticleTypes.PORTAL, pos2.x, pos2.y + 1.0, pos2.z, 30, 0.5, 0.5, 0.5, 0.1);
                            level.playSound(null, pos1.x, pos1.y, pos1.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                            level.playSound(null, pos2.x, pos2.y, pos2.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                            
                            clearMarks(stack); // สลับเสร็จ ล้างมาร์คทิ้ง
                            player.sendSystemMessage(Component.literal("Swapped!").withStyle(ChatFormatting.AQUA));
                        } else {
                            player.sendSystemMessage(Component.literal("A target died or escaped! Marks cleared.").withStyle(ChatFormatting.RED));
                            clearMarks(stack);
                        }
                    } else {
                        player.sendSystemMessage(Component.literal("Aim at a marked target (Needs 2 marks to swap)!").withStyle(ChatFormatting.YELLOW));
                    }
                } else {
                    // ถ้าเล็งใส่อากาศ + Shift ขวา
                    if (getMark1(stack) != null || getMark2(stack) != null) {
                        clearMarks(stack);
                        player.sendSystemMessage(Component.literal("Marks Cleared.").withStyle(ChatFormatting.RED));
                    } else {
                        setLiveChargeTicks(stack, 0);
                        player.startUsingItem(hand);
                    }
                }
                return InteractionResult.CONSUME;
            }
            
            // --- คลิกขวาปกติ (ไม่กดชิฟ) วาร์ปเข้าตีตัวที่เรืองแสง (ของเก่า) ---
            double maxTeleportRange = 32.0D + getBonusRange(stack);
            Vec3 start = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            Vec3 end = start.add(look.scale(maxTeleportRange)); 
            AABB searchBox = player.getBoundingBox().inflate(maxTeleportRange);

            LivingEntity target = null;
            double closestDist = maxTeleportRange * maxTeleportRange;

            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, searchBox, entity -> entity != player && entity.isAlive() && entity.hasEffect(MobEffects.GLOWING))) {
                AABB targetBox = e.getBoundingBox().inflate(3.5D); 
                Optional<Vec3> hitOpt = targetBox.clip(start, end); 
                
                if (hitOpt.isPresent()) {
                    double dist = start.distanceToSqr(hitOpt.get());
                    if (dist < closestDist) {
                        closestDist = dist;
                        target = e;
                    }
                }
            }

            if (target != null) {
                double pX = player.getX(), pY = player.getY(), pZ = player.getZ();
                double tX = target.getX(), tY = target.getY(), tZ = target.getZ();

                double dX = pX - tX, dY = player.getEyeY() - (tY + target.getEyeHeight()), dZ = pZ - tZ;
                double diffXZ = Math.sqrt(dX * dX + dZ * dZ);
                float yaw = (float) (Mth.atan2(dZ, dX) * (180D / Math.PI)) - 90.0F;
                float pitch = (float) -(Mth.atan2(dY, diffXZ) * (180D / Math.PI));
                player.setYRot(yaw); player.setXRot(pitch); player.setYHeadRot(yaw);

                player.teleportTo(tX, tY, tZ);
                target.teleportTo(pX, pY, pZ);
                target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, false, false));

                ((ServerLevel) level).sendParticles(ParticleTypes.PORTAL, pX, pY + 1.0, pZ, 35, 0.5D, 0.5D, 0.5D, 0.1D);
                ((ServerLevel) level).sendParticles(ParticleTypes.POOF, pX, pY + 1.0, pZ, 35, 0.5D, 0.5D, 0.5D, 0.1D);
                ((ServerLevel) level).sendParticles(ParticleTypes.GUST, pX, pY + 1.0, pZ, 5, 0.5D, 0.5D, 0.5D, 0.1D);
                player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 2, 1, false, false, false));

                ((ServerLevel) level).sendParticles(ParticleTypes.REVERSE_PORTAL, tX, tY + 1.0, tZ, 30, 0.5D, 0.5D, 0.5D, 0.1D);
                ((ServerLevel) level).sendParticles(ParticleTypes.POOF, tX, tY + 1.0, tZ, 35, 0.5D, 0.5D, 0.5D, 0.1D);
                ((ServerLevel) level).sendParticles(ParticleTypes.GUST, tX, tY + 1.0, tZ, 5, 0.5D, 0.5D, 0.5D, 0.1D);
                level.playSound(null, tX, tY, tZ, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.0F, 1.0F);

                player.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 0, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 1, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.SATURATION, 60, 0, false, false, true));

                if (target instanceof Vex) { 
                    target.discard(); 
                } else {
                    target.removeEffect(MobEffects.GLOWING); 
                }

                player.getCooldowns().addCooldown(stack, 10);
                return InteractionResult.SUCCESS;
                
            } 
            else {
                setLiveChargeTicks(stack, 0);
                player.startUsingItem(hand);
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.CONSUME;
    }
    

    // ==========================================
    // 3. ฟังก์ชันอัปเดตหลอดชาร์จ และปล่อยปุ่ม
    // ==========================================
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return 72000; }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) { return ItemUseAnimation.BLOCK; }

    @Override
    public void onUseTick(Level level, LivingEntity living, ItemStack stack, int remainingUseDuration) {
        super.onUseTick(level, living, stack, remainingUseDuration);
        
        int usedTicks = this.getUseDuration(stack, living) - remainingUseDuration;
        int displayTicks = Math.min(usedTicks, MAX_CHARGE_TICKS);
        setLiveChargeTicks(stack, displayTicks);

        if (!level.isClientSide() && living instanceof Player player) {
            player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 5, 2, false, false, true));

            if (usedTicks % 5 == 0) {
                ((ServerLevel) level).sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 
                        50, 3.5D, 1.0D, 3.5D, 0.0D);

                AABB aoeBox = player.getBoundingBox().inflate(7.0D);
                List<LivingEntity> aoeTargets = level.getEntitiesOfClass(LivingEntity.class, aoeBox, e -> e != player && e.isAlive());
                for (LivingEntity e : aoeTargets) {
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false, true)); 
                    e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, false, true)); 
                }
            }

            if (usedTicks == MAX_CHARGE_TICKS) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), 
                        SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.PLAYERS, 1.0F, 1.2F);
                ((ServerLevel) level).sendParticles(ParticleTypes.TRIAL_OMEN, 
                        player.getX(), player.getY() + 0.0, player.getZ(), 
                        25, 0.5D, 0.2D, 0.5D, 0.15D);
            }
        }
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        int usedTicks = this.getUseDuration(stack, living) - timeLeft;
        clearLiveChargeTicks(stack);

        if (!level.isClientSide() && living instanceof Player player) {
            float charge = Math.min(usedTicks / (float) MAX_CHARGE_TICKS, 1.0F);
            
            double bonusRange = getBonusRange(stack);
            float bonusDamage = getBonusDamage(stack);

            double distance = 5.0D + ((27.0D + bonusRange) * charge); 
            float damage = 4.0F + (10.0F * charge) + bonusDamage; 

            Vec3 start = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            Vec3 end = start.add(look.scale(distance));

            BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            Vec3 spawnPos = hit.getLocation();

            ServerLevel serverLevel = (ServerLevel) level;
            int particleCount = (int) (distance * 3); 

            for (int i = 0; i <= particleCount; i++) {
                double lerp = (double) i / particleCount;
                double pX = Mth.lerp(lerp, start.x, end.x);
                double pY = Mth.lerp(lerp, start.y, end.y);
                double pZ = Mth.lerp(lerp, start.z, end.z);
                
                serverLevel.sendParticles(ParticleTypes.SQUID_INK, pX, pY, pZ, 2, 0.15D, 0.15D, 0.15D, 0.05D);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.5F);

            AABB searchBox = player.getBoundingBox().expandTowards(look.scale(distance)).inflate(2.0D);
            List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, searchBox, e -> e != player && e.isAlive());

            boolean hitAnyMob = false;

            for (LivingEntity target : targets) {
                AABB targetBox = target.getBoundingBox().inflate(3.5D);
                if (targetBox.clip(start, end).isPresent() || targetBox.contains(start)) {
                    
                    hitAnyMob = true; 
                    
                    target.hurt(level.damageSources().indirectMagic(player, player), damage); 
                    
                    // --- ระบบซ่อมดาบเมื่อศัตรูตายจากคลื่นดาบ ---
                    if (target.isDeadOrDying() || target.getHealth() <= 0.0F) {
                        addKill(stack); // เรียก addKill ปุ๊บ อัปเดตสเตตัสดาบปั๊บ!
                        
                        if (stack.isDamageableItem()) {
                            stack.setDamageValue(Math.max(0, stack.getDamageValue() - 4));
                            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.0, player.getZ(), 5, 0.3D, 0.3D, 0.3D, 0.1D);
                            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.2F);
                        }
                    }

                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, true, true));
                    target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, true, true));
                    target.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 1, false, true, true));
                    target.addEffect(new MobEffectInstance(MobEffects.WITHER, 40, 1, false, true, true));
                    
                    serverLevel.sendParticles(ParticleTypes.POOF, target.getX(), target.getY() + 1.0D, target.getZ(), 10, 0.2D, 0.2D, 0.2D, 0.1D);
                }
            }

            if (!hitAnyMob) {
                Vex vex = EntityType.VEX.create(level, EntitySpawnReason.COMMAND);
                if (vex != null) {
                    vex.setPos(spawnPos.x, spawnPos.y + 0.1D, spawnPos.z);
                    vex.setNoAi(true);
                    vex.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
                    vex.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
                    
                    level.addFreshEntity(vex);
                    
                    level.playSound(null, spawnPos.x, spawnPos.y, spawnPos.z, SoundEvents.VEX_AMBIENT, SoundSource.PLAYERS, 1.0F, 1.0F);
                    serverLevel.sendParticles(ParticleTypes.POOF, spawnPos.x, spawnPos.y + 0.5D, spawnPos.z, 20, 0.3D, 0.3D, 0.3D, 0.1D);
                }
            }

            player.getCooldowns().addCooldown(stack, 15); 
            return true;
        }
        return false;
    }

    // ==========================================
    // 4. การแสดงผลหลอด UI และ Tooltip
    // ==========================================
    
    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay displayComponent,
            Consumer<Component> textConsumer,
            TooltipFlag type) {

        int killCount = getKillCount(stack);
        double bonusDamage = getBonusDamage(stack);
        double bonusRange = getBonusRange(stack);
        super.appendHoverText(stack, context, displayComponent, textConsumer, type);

        textConsumer.accept(Component.literal("Kills: " + killCount).withStyle(ChatFormatting.DARK_RED));
        textConsumer.accept(Component.literal("Bonus Damage: " + String.format("%.2f", bonusDamage)).withStyle(ChatFormatting.RED));
        textConsumer.accept(Component.literal("Bonus Distance: " + String.format("%.2f", bonusRange)).withStyle(ChatFormatting.RED));
        textConsumer.accept(Component.literal("Dodge!").withStyle(ChatFormatting.DARK_PURPLE));
        textConsumer.accept(Component.literal("The Power of The End").withStyle(ChatFormatting.DARK_PURPLE));
    }
    

    @Override
    public boolean isBarVisible(ItemStack stack) { return getLiveChargeTicks(stack) >= 0 || super.isBarVisible(stack); }

    @Override
    public int getBarWidth(ItemStack stack) {
        int chargeTicks = getLiveChargeTicks(stack);
        if (chargeTicks >= 0) {
            float charge = Math.min(chargeTicks / (float) MAX_CHARGE_TICKS, 1.0F);
            return Math.round(13.0F * charge);
        }
        return super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int chargeTicks = getLiveChargeTicks(stack);
        if (chargeTicks >= 0) {
            float charge = Math.min(chargeTicks / (float) MAX_CHARGE_TICKS, 1.0F);
            return lerpColor(CHARGE_BAR_COLOR_EMPTY, CHARGE_BAR_COLOR_FULL, charge);
        }
        return super.getBarColor(stack);
    }

    private static int getChargeKey(ItemStack stack) { return System.identityHashCode(stack); }
    private static void setLiveChargeTicks(ItemStack stack, int chargeTicks) { LIVE_CHARGE_BAR_TICKS.put(getChargeKey(stack), Math.max(0, Math.min(chargeTicks, MAX_CHARGE_TICKS))); }
    private static void clearLiveChargeTicks(ItemStack stack) { LIVE_CHARGE_BAR_TICKS.remove(getChargeKey(stack)); }
    private static int getLiveChargeTicks(ItemStack stack) { return LIVE_CHARGE_BAR_TICKS.getOrDefault(getChargeKey(stack), -1); }

    private static int lerpColor(int fromColor, int toColor, float t) {
        int fromR = (fromColor >> 16) & 0xFF, fromG = (fromColor >> 8) & 0xFF, fromB = fromColor & 0xFF;
        int toR = (toColor >> 16) & 0xFF, toG = (toColor >> 8) & 0xFF, toB = toColor & 0xFF;
        return ((Math.round(fromR + (toR - fromR) * t)) << 16) | ((Math.round(fromG + (toG - fromG) * t)) << 8) | Math.round(fromB + (toB - fromB) * t);
    }
}