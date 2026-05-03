package com.rushberry.dodgesword;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class DodgeSwordEvents {

    public static void initialize() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(DodgeSwordEvents::onKilledOtherEntity);
        ServerPlayerEvents.AFTER_RESPAWN.register(DodgeSwordEvents::onAfterRespawn);
    }
    
    private static void onKilledOtherEntity(ServerLevel world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) {
        // 1. เช็คว่าคนที่ฆ่าคือผู้เล่นหรือไม่
        if (!(entity instanceof Player player)) {
            return;
        }

        // 2. ป้องกันการฟาร์ม Kill จากผู้เล่นด้วยกันเอง (ตามโค้ดต้นฉบับของคุณ)
        if (killedEntity instanceof Player) {
            return;
        }

        // 3. เช็คว่าไอเทมในมือหลักคือ Dodge Sword หรือไม่
        ItemStack mainHand = player.getMainHandItem();
        if (!(mainHand.getItem() instanceof DodgeSwordItem)) {
            return;
        }

        // 4. เพิ่ม Kill Count โดยเรียกใช้ฟังก์ชันจากไฟล์ DodgeSwordItem
        DodgeSwordItem.addKill(mainHand);
    }

    private static void onAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        // เช็ค !alive เพื่อให้รีเซ็ตเฉพาะตอน "ตาย" จริงๆ (ป้องกันการรีเซ็ตตอนแค่วาร์ปข้ามมิติ Nether/End)
        if (!alive) {
            resetDodgeSwords(newPlayer);
        }
    }

    private static void resetDodgeSwords(ServerPlayer player) {
        // ลูปเช็คไอเทมทุกช่องในตัวผู้เล่น
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);

            // ถ้าเจอ Dodge Sword
            if (stack.getItem() instanceof DodgeSwordItem) {
                // รีเซ็ตค่า Kills ให้กลับเป็น 0
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt("DodgeSwordKills", 0));
            }
        }
    }
}