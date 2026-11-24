package io.github.glrp.nanocore.stats;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.generator.structure.Structure;

/** Ids of places for stats and quests ("monument", "trial_chambers"), read through the registry. */
public final class Places {

    private Places() {
    }

    /** The structure's id without the namespace, or "unknown" if it isn't registered (custom worlds). */
    public static String structureId(Structure s) {
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE).getKey(s);
        return key == null ? "unknown" : key.getKey();
    }
}
