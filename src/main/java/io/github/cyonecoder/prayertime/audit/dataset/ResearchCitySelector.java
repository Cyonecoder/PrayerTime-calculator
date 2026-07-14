package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a {@code --cities} / {@code --inventory} value used by the research CLI
 * into a concrete list of application city slugs. Accepts a comma-separated
 * explicit list or one of the named selectors:
 *
 * <ul>
 *   <li>{@code all-candidates} / {@code all-67} — every candidate in the inventory</li>
 *   <li>{@code all-mapped} — every city with a Habous mapping</li>
 *   <li>{@code all-new-verified} — verified mappings that are new (not existing-sec)</li>
 *   <li>{@code all-sec-supported} — cities with committed sec-approach offsets</li>
 *   <li>{@code existing-sec} — the 14 original sec-approach cities</li>
 * </ul>
 *
 * Research/audit only; it reads bundled resources and never touches the network.
 */
public final class ResearchCitySelector {

    private static final String INVENTORY = "/research/habous-candidate-inventory.json";
    private static final String SEC = "/research/morocco-habous-secaproach.json";
    private static final String BASE14 = "/research/morocco-habous-secaproach-base14.json";

    private ResearchCitySelector() {
    }

    /**
     * @param value a selector name or comma-separated slug list
     * @return the resolved, de-duplicated, order-preserving slug list
     * @throws IllegalArgumentException if the value is blank or resolves to nothing
     */
    public static List<String> resolve(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("no --cities/--inventory value given");
        }
        List<String> resolved = switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all-candidates", "all-67" -> inventorySlugs(null);
            case "all-mapped" -> HabousCityMapping.load().mappedCityIds();
            case "all-new-verified" -> newVerified();
            case "all-sec-supported" -> offsetKeys(SEC);
            case "existing-sec" -> offsetKeys(BASE14);
            default -> explicitList(value);
        };
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("selector resolved to no cities: " + value);
        }
        return resolved;
    }

    /** The verified mappings whose cities are new candidates (not existing-sec). */
    private static List<String> newVerified() {
        Set<String> verified = new LinkedHashSet<>(HabousCityMapping.load().verifiedCityIds());
        Set<String> existing = new LinkedHashSet<>(offsetKeys(BASE14));
        List<String> out = new ArrayList<>();
        for (String slug : inventorySlugs("new-training")) {
            if (verified.contains(slug) && !existing.contains(slug)) {
                out.add(slug);
            }
        }
        return out;
    }

    /** Inventory slugs, optionally filtered by {@code secState}. */
    private static List<String> inventorySlugs(String secState) {
        JsonObject inv = readResource(INVENTORY);
        List<String> out = new ArrayList<>();
        for (JsonElement e : inv.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            if (secState == null || secState.equals(c.get("secState").getAsString())) {
                out.add(c.get("canonicalSlug").getAsString());
            }
        }
        return out;
    }

    private static List<String> offsetKeys(String resource) {
        JsonObject root = readResource(resource);
        return new ArrayList<>(root.getAsJsonObject("offsets").keySet());
    }

    private static List<String> explicitList(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static JsonObject readResource(String resource) {
        try (InputStream in = ResearchCitySelector.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resource);
            }
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }
}
