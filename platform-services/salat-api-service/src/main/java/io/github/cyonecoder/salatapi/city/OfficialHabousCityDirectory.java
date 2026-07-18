package io.github.cyonecoder.salatapi.city;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyonecoder.salatapi.habous.model.HabousVille;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Group 8.1 — the official Habous ville directory: every numeric ville id and
 * its exact Arabic name, as published in the live Habous page's city dropdown.
 * Loaded from the bundled {@code /cities/habous-ville-index.json}.
 *
 * <p>All 191 villes are <strong>eligible for official data collection</strong>.
 * Membership here says nothing about sec-approach support: only 48 cities have
 * offsets, and 131 villes have no verified application mapping at all, so they
 * can be collected but never compared. Never present this group as
 * sec-approach-supported — see {@code CitySupportService}.
 */
@Component
public class OfficialHabousCityDirectory {

    /** The expected directory size; a mismatch fails startup rather than collecting a partial set. */
    public static final int EXPECTED_VILLE_COUNT = 191;

    private static final String DEFAULT_RESOURCE = "/cities/habous-ville-index.json";

    private final List<HabousVille> villes;
    private final Map<String, HabousVille> byId;

    public OfficialHabousCityDirectory() {
        this(DEFAULT_RESOURCE);
    }

    OfficialHabousCityDirectory(String resourcePath) {
        VilleIndexFile file = load(resourcePath);

        List<HabousVille> loaded = new ArrayList<>();
        Map<String, HabousVille> ids = new LinkedHashMap<>();
        for (VilleDto dto : file.villes()) {
            HabousVille ville = new HabousVille(dto.habousCityId(), dto.habousArabicName());
            if (ids.putIfAbsent(ville.habousCityId(), ville) != null) {
                throw new IllegalStateException("Duplicate Habous ville id: " + ville.habousCityId());
            }
            loaded.add(ville);
        }

        // The file states its own count; trust neither it nor the array alone.
        if (file.villeCount() != loaded.size()) {
            throw new IllegalStateException(
                    "Declared villeCount " + file.villeCount() + " does not match "
                            + loaded.size() + " parsed villes in " + resourcePath);
        }
        if (loaded.size() != EXPECTED_VILLE_COUNT) {
            throw new IllegalStateException(
                    "Expected " + EXPECTED_VILLE_COUNT + " official Habous villes in "
                            + resourcePath + " but found " + loaded.size());
        }

        this.villes = List.copyOf(loaded);
        this.byId = Map.copyOf(ids);
    }

    /** @return all official villes, in directory order (immutable) */
    public List<HabousVille> all() {
        return villes;
    }

    /** @return the number of official villes (191) */
    public int count() {
        return villes.size();
    }

    /**
     * @param habousCityId an official ville id
     * @return the ville, or empty if the id is not in the official directory
     */
    public Optional<HabousVille> findById(String habousCityId) {
        if (habousCityId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(habousCityId.trim()));
    }

    /** @return {@code true} if the id is an official ville eligible for collection */
    public boolean isOfficialVille(String habousCityId) {
        return findById(habousCityId).isPresent();
    }

    private VilleIndexFile load(String resourcePath) {
        try (InputStream in = OfficialHabousCityDirectory.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Ville index resource not found: " + resourcePath);
            }
            VilleIndexFile file = new ObjectMapper().readValue(in, VilleIndexFile.class);
            if (file == null || file.villes() == null || file.villes().isEmpty()) {
                throw new IllegalStateException("No villes in resource: " + resourcePath);
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ville index: " + resourcePath, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VilleIndexFile(int villeCount, List<VilleDto> villes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VilleDto(String habousCityId, String habousArabicName) {}
}
