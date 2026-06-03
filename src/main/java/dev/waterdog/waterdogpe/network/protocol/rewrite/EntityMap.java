/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.protocol.rewrite;

import it.unimi.dsi.fastutil.longs.LongListIterator;
import org.cloudburstmc.protocol.bedrock.data.PlayerListPacketType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorLink;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraAttachToEntityInstruction;
import org.cloudburstmc.protocol.bedrock.data.payload.shape.ShapeDataPayload;
import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.Arrays;
import java.util.Collection;
import java.util.ListIterator;
import java.util.function.LongConsumer;

import static dev.waterdog.waterdogpe.network.protocol.Signals.mergeSignals;

/**
 * Class to map the proper entityIds to entity-related packets.
 */
public class EntityMap implements BedrockPacketHandler {
    private static final Collection<ActorDataType<Long>> ENTITY_DATA_FIELDS = Arrays.asList(
            ActorDataTypes.OWNER,
            ActorDataTypes.TARGET,
            ActorDataTypes.LEASH_HOLDER,
            ActorDataTypes.TARGET_A,
            ActorDataTypes.TARGET_B,
            ActorDataTypes.TARGET_C,
            ActorDataTypes.TRADE_TARGET,
            ActorDataTypes.BALLOON_ANCHOR,
            ActorDataTypes.AGENT
    );

    private final ProxiedPlayer player;
    private final RewriteData rewrite;

    public EntityMap(ProxiedPlayer player) {
        this.player = player;
        this.rewrite = player.getRewriteData();
    }

    public PacketSignal doRewrite(BedrockPacket packet) {
        return this.player.canRewrite() ? packet.handle(this) : PacketSignal.UNHANDLED;
    }

    private PacketSignal rewriteId(long from, LongConsumer setter) {
        long rewriteId = PlayerRewriteUtils.rewriteId(from, this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
        if (rewriteId == from) {
            return PacketSignal.UNHANDLED;
        }
        setter.accept(rewriteId);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(MoveActorAbsolutePacket packet) {
        return rewriteId(packet.getMoveData().getActorRuntimeID(), (t) -> packet.getMoveData().setActorRuntimeID(t));
    }

    @Override
    public PacketSignal handle(ActorEventPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(MobEffectPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(UpdateAttributesPacket packet) {
        return rewriteId(packet.getRuntimeID(), packet::setRuntimeID);
    }

    @Override
    public PacketSignal handle(MobEquipmentPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(MobArmorEquipmentPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(PlayerActionPacket packet) {
        return rewriteId(packet.getPlayerRuntimeID(), packet::setPlayerRuntimeID);
    }

    @Override
    public PacketSignal handle(SetActorDataPacket packet) {
        PacketSignal signal = rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
        PacketSignal metaSignal = this.rewriteMetadata(packet.getActorData());
        return mergeSignals(signal, metaSignal);
    }

    @Override
    public PacketSignal handle(SetActorMotionPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(MoveActorDeltaPacket packet) {
        return rewriteId(packet.getData().getActorRuntimeID(), (t) -> packet.getData().setActorRuntimeID(t));
    }

    @Override
    public PacketSignal handle(SetLocalPlayerAsInitializedPacket packet) {
        return rewriteId(packet.getPlayerID(), packet::setPlayerID);
    }

    @Override
    public PacketSignal handle(AddPlayerPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
        PacketSignal signal1 = rewriteId(packet.getTargetActorID(), packet::setTargetActorID);

        PacketSignal signal2 = PacketSignal.UNHANDLED;

        ListIterator<ActorLink> iterator = packet.getActorLinks().listIterator();
        while (iterator.hasNext()) {
            ActorLink entityLink = iterator.next();
            long from = PlayerRewriteUtils.rewriteId(entityLink.getTargetA(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            long to = PlayerRewriteUtils.rewriteId(entityLink.getTargetB(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (entityLink.getTargetA() != from || entityLink.getTargetB() != to) {
                iterator.set(new ActorLink(from, to, entityLink.getType(), entityLink.isImmediate(), entityLink.isPassengerInitiated()));
                signal2 = PacketSignal.HANDLED;
            }
        }

        PacketSignal signal3 = this.rewriteMetadata(packet.getActorData());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED || signal3 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddActorPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
        PacketSignal signal1 = rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);

        PacketSignal signal2 = PacketSignal.UNHANDLED;

        ListIterator<ActorLink> iterator = packet.getActorLinks().listIterator();
        while (iterator.hasNext()) {
            ActorLink entityLink = iterator.next();
            long from = PlayerRewriteUtils.rewriteId(entityLink.getTargetA(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            long to = PlayerRewriteUtils.rewriteId(entityLink.getTargetB(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (entityLink.getTargetA() != from || entityLink.getTargetB() != to) {
                iterator.set(new ActorLink(from, to, entityLink.getType(), entityLink.isImmediate(), entityLink.isPassengerInitiated()));
                signal2 = PacketSignal.HANDLED;
            }
        }

        PacketSignal signal4 = this.rewriteMetadata(packet.getActorData());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED || signal4 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddItemActorPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
        PacketSignal signal1 = rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
        PacketSignal signal2 = this.rewriteMetadata(packet.getEntityData());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddPaintingPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
        PacketSignal signal1 = rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(RemoveActorPacket packet) {
        return rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
    }

    @Override
    public PacketSignal handle(BossEventPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getPlayerID(), packet::setPlayerID);
        PacketSignal signal1 = rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(TakeItemActorPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getActorRuntimeID(), packet::setActorRuntimeID);
        PacketSignal signal1 = rewriteId(packet.getItemRuntimeID(), packet::setItemRuntimeID);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(MovePlayerPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getPlayerRuntimeID(), packet::setPlayerRuntimeID);
        PacketSignal signal1 = rewriteId(packet.getRidingRuntimeID(), packet::setRidingRuntimeID);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(InteractPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(PlayerLocationPacket packet) {
        return rewriteId(packet.getTargetActorID(), packet::setTargetActorID);
    }

    @Override
    public PacketSignal handle(SetActorLinkPacket packet) {
        ActorLink entityLink = packet.getLink();
        long from = PlayerRewriteUtils.rewriteId(entityLink.getTargetA(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
        long to = PlayerRewriteUtils.rewriteId(entityLink.getTargetB(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());

        if (from != entityLink.getTargetA() || to != entityLink.getTargetB()) {
            packet.setLink(new ActorLink(from, to, entityLink.getType(), entityLink.isImmediate(), entityLink.isPassengerInitiated()));
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AnimatePacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(AdventureSettingsPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(PlayerListPacket packet) {
        if (packet.getAction() != PlayerListPacketType.ADD) {
            return PacketSignal.UNHANDLED;
        }

        PacketSignal signal = PacketSignal.UNHANDLED;

        for (PlayerListPacket.Entry entry : packet.getEntries()) {
            long rewriteId = PlayerRewriteUtils.rewriteId(entry.getTargetActorID(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (rewriteId != entry.getTargetActorID()) {
                signal = PacketSignal.HANDLED;
                entry.setTargetActorID(rewriteId);
            }
        }
        return signal;
    }

    @Override
    public PacketSignal handle(UpdateTradePacket packet) {
        PacketSignal signal0 = rewriteId(packet.getEntityUniqueId(), packet::setEntityUniqueId);
        PacketSignal signal1 = rewriteId(packet.getLastTradingPlayer(), packet::setLastTradingPlayer);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(RespawnPacket packet) {
        return rewriteId(packet.getPlayerRuntimeId(), packet::setPlayerRuntimeId);
    }

    @Override
    public PacketSignal handle(EmoteListPacket packet) {
        return rewriteId(packet.getRuntimeId(), packet::setRuntimeId);
    }

    public PacketSignal handle(NpcDialoguePacket packet) {
        return rewriteId(packet.getNpcId(), packet::setNpcId);
    }

    public PacketSignal handle(NpcRequestPacket packet) {
        return rewriteId(packet.getNpcRuntimeID(), packet::setNpcRuntimeID);
    }

    @Override
    public PacketSignal handle(EmotePacket packet) {
        return rewriteId(packet.getActorRuntimeId(), packet::setActorRuntimeId);
    }

    @Override
    public PacketSignal handle(SpawnParticleEffectPacket packet) {
        return rewriteId(packet.getActorId(), packet::setActorId);
    }

    @Override
    public PacketSignal handle(ActorPickRequestPacket packet) {
        return rewriteId(packet.getActorID(), packet::setActorID);
    }

    /*@Override
    public PacketSignal handle(EventPacket packet) {
        return rewriteId(packet., packet::setUniqueActorId);
    }*/

    @Override
    public PacketSignal handle(UpdatePlayerGameTypePacket packet) {
        return rewriteId(packet.getTargetPlayer(), packet::setTargetPlayer);
    }

    @Override
    public PacketSignal handle(UpdateAbilitiesPacket packet) {
        return rewriteId(packet.getData().getTargetPlayerRawId(), (t) -> packet.getData().setTargetPlayerRawId(t));
    }

    @Override
    public PacketSignal handle(ClientCheatAbilityPacket packet) {
        return rewriteId(packet.getData().getTargetPlayerRawId(), (t) -> packet.getData().setTargetPlayerRawId(t));
    }

    @Override
    public PacketSignal handle(PlayerUpdateEntityOverridesPacket packet) {
        return rewriteId(packet.getTargetID(), packet::setTargetID);
    }

    @Override
    public PacketSignal handle(LevelSoundEventPacket packet) {
        return rewriteId(packet.getActorUniqueId(), packet::setActorUniqueId);
    }

    @Override
    public PacketSignal handle(AnimateEntityPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        LongListIterator iterator = packet.getRuntimeIds().listIterator();
        while (iterator.hasNext()) {
            PacketSignal returnedSignal = rewriteId(iterator.nextLong(), iterator::set);
            signal = mergeSignals(signal, returnedSignal);
        }
        return signal;
    }

    @Override
    public PacketSignal handle(MovementEffectPacket packet) {
        return rewriteId(packet.getTargetRuntimeID(), packet::setTargetRuntimeID);
    }

    @Override
    public PacketSignal handle(ClientMovementPredictionSyncPacket packet) {
        return rewriteId(packet.getActorID(), packet::setActorID);
    }

    @Override
    public PacketSignal handle(UpdateEquipPacket packet) {
        return rewriteId(packet.getEntityUniqueId(), packet::setEntityUniqueId);
    }

    @Override
    public PacketSignal handle(CameraInstructionPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        CameraAttachToEntityInstruction attachInstruction = packet.getAttachToEntityInstruction();
        if (attachInstruction != null) {
            PacketSignal returnedSignal = rewriteId(attachInstruction.getEntityActorID(), attachInstruction::setEntityActorID);
            signal = mergeSignals(signal, returnedSignal);
        }
        return signal;
    }

    @Override
    public PacketSignal handle(PrimitiveShapesPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        for (ShapeDataPayload shape : packet.getShapes()) {
            Long attachedActorId = shape.getAttachedToEntityID();
            if (attachedActorId != null) {
                PacketSignal returnedSignal = rewriteId(attachedActorId, shape::setAttachedToEntityID);
                signal = mergeSignals(signal, returnedSignal);
            }
        }
        return signal;
    }

    private PacketSignal rewriteMetadata(ActorDataMap metadata) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        for (ActorDataType<Long> data : ENTITY_DATA_FIELDS) {
            Long id = metadata.get(data);
            if (id != null) {
                long rewriteId = PlayerRewriteUtils.rewriteId(id, this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
                if (rewriteId != id) {
                    metadata.put(data, rewriteId);
                    signal = PacketSignal.HANDLED;
                }
            }
        }
        return signal;
    }
}
