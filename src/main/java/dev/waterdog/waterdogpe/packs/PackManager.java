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

package dev.waterdog.waterdogpe.packs;

import io.netty.buffer.Unpooled;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.data.PackType;
import org.cloudburstmc.protocol.bedrock.data.payload.experiment.ExperimentToggle;
import org.cloudburstmc.protocol.bedrock.data.payload.experiment.Experiments;
import org.cloudburstmc.protocol.bedrock.data.payload.pack.PackInstanceId;
import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.event.defaults.ResourcePacksRebuildEvent;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.packs.types.ZipResourcePack;
import dev.waterdog.waterdogpe.utils.FileUtils;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PackManager {

    private static final long CHUNK_SIZE = 102400; // 100 KB

    private static final PathMatcher ZIP_PACK_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.{zip,mcpack}");
    private static final PackInstanceId EDU_PACK = new PackInstanceId("0fba4063-dba1-4281-9b89-ff9390653530", "1.0.0", "");

    private final ProxyServer proxy;
    @Getter
    private final Map<UUID, ResourcePack> packs = new HashMap<>();
    @Getter
    private final Map<String, ResourcePack> packsByIdVer = new HashMap<>();

    @Getter
    private final ResourcePacksInfoPacket packsInfoPacket = new ResourcePacksInfoPacket();
    @Getter
    private final ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();

    public PackManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void loadPacks(Path packsDirectory) {
        Preconditions.checkNotNull(packsDirectory, "Packs directory can not be null!");
        Preconditions.checkArgument(Files.isDirectory(packsDirectory), packsDirectory + " must be directory!");
        this.proxy.getLogger().info("Loading resource packs!");

        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(packsDirectory);
            for (Path path : stream) {
                ResourcePack resourcePack = this.constructPack(path);
                if (resourcePack != null) {
                    String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getPackManifest().getHeader().getVersion();
                    this.packsByIdVer.put(packIdVer, resourcePack);
                    this.packs.put(resourcePack.getPackId(), resourcePack);
                    this.proxy.getLogger().info("Loaded resource pack: " + resourcePack.getPackManifest().getHeader().getName() + " v" + resourcePack.getPackManifest().getHeader().getVersion());
                }
            }
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not load packs!", e);
        }

        this.rebuildPackets();
        this.proxy.getLogger().info("Loaded " + this.packs.size() + " packs!");
    }

    private ResourcePack constructPack(Path packPath) {
        Class<? extends ResourcePack> loader = this.getPackLoader(packPath);
        if (loader == null) {
            return null;
        }

        try {
            ResourcePack pack = this.loadPack(packPath, loader);
            if (pack != null) {
                return pack;
            }
            this.proxy.getLogger().error("Resource pack manifest.json is invalid or was not found in " + packPath.getFileName() + ", please make sure that you zip the content of the pack and not the folder! Read more on troubleshooting here: https://docs.waterdog.dev/books/waterdogpe-setup/page/troubleshooting");
        } catch (Exception e) {
            this.proxy.getLogger().error("Can not load resource pack from: " + packPath.getFileName(), e);
        }
        return null;
    }

    private ResourcePack loadPack(Path packPath, Class<? extends ResourcePack> clazz) throws Exception {
        ResourcePack pack = clazz.getDeclaredConstructor(Path.class).newInstance(packPath);
        if (!pack.loadManifest() || !pack.getPackManifest().validate()) {
            return null;
        }

        File contentKeyFile = new File(packPath.getParent().toFile(), packPath.toFile().getName() + ".key");
        pack.setContentKey(contentKeyFile.exists() ? Files.readString(contentKeyFile.toPath(), StandardCharsets.UTF_8).replace("\n", "") : "");

        if (this.proxy.getConfiguration().getPackCacheSize() >= (pack.getPackSize() / FileUtils.INT_MEGABYTE)) {
            pack.saveToCache();
        }
        return pack;
    }

    /**
     * We are currently supporting only zipped resource packs
     *
     * @param path to resource pack.
     * @return class which will be used to load pack.
     */
    public Class<? extends ResourcePack> getPackLoader(Path path) {
        if (ZIP_PACK_MATCHER.matches(path)) {
            return ZipResourcePack.class;
        }
        return null;
    }

    public boolean registerPack(ResourcePack resourcePack) {
        Preconditions.checkNotNull(resourcePack, "Resource pack can not be null!");
        Preconditions.checkArgument(resourcePack.getPackManifest().validate(), "Resource pack has invalid manifest!");

        if (this.packs.get(resourcePack.getPackId()) != null) {
            return false;
        }

        String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getVersion();
        this.packsByIdVer.put(packIdVer, resourcePack);
        this.packs.put(resourcePack.getPackId(), resourcePack);
        this.rebuildPackets();
        return true;
    }

    public boolean unregisterPack(UUID packId) {
        ResourcePack resourcePack = this.packs.remove(packId);
        if (resourcePack == null) {
            return false;
        }

        String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getVersion();
        this.packsByIdVer.remove(packIdVer);
        this.rebuildPackets();
        return true;
    }

    public void rebuildPackets() {
        this.packsInfoPacket.setResourcePackRequired(this.proxy.getConfiguration().isForceServerPacks());
        this.packsInfoPacket.setForceServerPacksEnabled(this.proxy.getConfiguration().isForceServerPacks());
        this.packsInfoPacket.setWorldTemplateUUID(UUID.randomUUID());
        this.packsInfoPacket.setWorldTemplateVersion("");
        this.packsInfoPacket.setForceDisableVibrantVisuals(false);
        this.stackPacket.setTexturePackRequired(this.proxy.getConfiguration().isOverwriteClientPacks());

        this.packsInfoPacket.getBehaviorPacks().clear();
        this.packsInfoPacket.getResourcePacks().clear();

        this.stackPacket.getTexturePackList().clear();
        this.stackPacket.setExperiments(new Experiments());

        this.stackPacket.setBaseGameVersion("");

        for (ResourcePack pack : this.packs.values()) {
            ResourcePacksInfoPacket.Entry infoEntry = new ResourcePacksInfoPacket.Entry(pack.getPackId(), pack.getVersion().toString(),
                    pack.getPackSize(), pack.getContentKey(), "", pack.getContentKey().isEmpty() ? "" : pack.getPackId().toString(), false, false, false, null);
            PackInstanceId stackEntry = new PackInstanceId(pack.getPackId().toString(), pack.getVersion().toString(), "");
            if (pack.getType().equals(ResourcePack.TYPE_RESOURCES)) {
                this.packsInfoPacket.getResourcePacks().add(infoEntry);
                this.stackPacket.getTexturePackList().add(stackEntry);
            }
        }

        if (this.proxy.getConfiguration().enableEducationFeatures()) {
            this.stackPacket.getTexturePackList().add(EDU_PACK);
        }
        ResourcePacksRebuildEvent event = new ResourcePacksRebuildEvent(this.packsInfoPacket, this.stackPacket);
        this.proxy.getEventManager().callEvent(event);
    }

    public ResourcePackDataInfoPacket packInfoFromIdVer(String idVersion) {
        ResourcePack resourcePack = this.packsByIdVer.get(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackDataInfoPacket packet = new ResourcePackDataInfoPacket();
        packet.setPackId(resourcePack.getPackId());
        packet.setPackVersion(resourcePack.getVersion().toString());
        packet.setChunkSize(CHUNK_SIZE);
        packet.setNumberOfChunks((resourcePack.getPackSize() - 1) / packet.getChunkSize() + 1);
        packet.setFileSize(resourcePack.getPackSize());
        packet.setFileHash(resourcePack.getHash());
        if (resourcePack.getType().equals(ResourcePack.TYPE_RESOURCES)) {
            packet.setPackType(PackType.RESOURCES);
        } else if (resourcePack.getType().equals(ResourcePack.TYPE_DATA)) {
            packet.setPackType(PackType.ADDON);
        }
        return packet;
    }

    public ResourcePackChunkDataPacket packChunkDataPacket(String idVersion, ResourcePackChunkRequestPacket from) {
        ResourcePack resourcePack = this.packsByIdVer.get(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackChunkDataPacket packet = new ResourcePackChunkDataPacket();
        packet.setPackId(from.getPackId());
        packet.setPackVersion(from.getPackVersion());
        packet.setChunkID(from.getChunk());
        packet.setChunkData(Unpooled.wrappedBuffer(resourcePack.getChunk((int) CHUNK_SIZE * from.getChunk(), (int) CHUNK_SIZE)));
        packet.setByteOffset(CHUNK_SIZE * from.getChunk());
        return packet;
    }

}
