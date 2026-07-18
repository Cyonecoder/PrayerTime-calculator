package io.github.cyonecoder.salatapi.city;

import io.github.cyonecoder.salatapi.city.model.City;
import io.github.cyonecoder.salatapi.habous.model.HabousVille;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the four city groups distinct and answers "what can this service actually
 * do for city X?".
 *
 * <p>The groups are strictly nested — {@code 48 sec-approach ⊆ 60 mapped ⊆ 67
 * registry}, and the 60 ville targets ⊆ the 191 official villes — which makes it
 * easy to conflate them. This service exists so that never happens: the 191
 * official villes are <strong>not</strong> sec-approach cities, and neither are
 * the 67 registry cities. Only 48 are.
 */
@Service
public class CitySupportService {

    private static final Logger log = LoggerFactory.getLogger(CitySupportService.class);

    /** What the service can do for a given city. */
    public enum SupportLevel {
        /** Official data and sec-approach both available; comparable. (48 cities.) */
        SEC_APPROACH_AND_OFFICIAL,
        /** Verified ville mapping, but no accepted offsets: official data only. (12 cities.) */
        OFFICIAL_ONLY,
        /** In the application registry but with no verified ville mapping. (7 cities.) */
        REGISTRY_ONLY,
        /** An official ville with no application mapping: collectable, never comparable. (131 villes.) */
        OFFICIAL_VILLE_UNMAPPED,
        /** Not a known application city or official ville. */
        UNKNOWN
    }

    /**
     * The support answer for one application city.
     *
     * @param citySlug        the application slug, if it is a registry city
     * @param level           what the service can do
     * @param habousCityId    the verified ville id, when one exists
     * @param secApproachSupported whether the city has accepted six-prayer offsets
     */
    public record CitySupport(
            Optional<String> citySlug,
            SupportLevel level,
            Optional<String> habousCityId,
            boolean secApproachSupported) {}

    private final MoroccoCityRegistry registry;
    private final OfficialHabousCityDirectory directory;
    private final ApplicationHabousMapping mapping;
    private final SecApproachParameters parameters;

    public CitySupportService(
            MoroccoCityRegistry registry,
            OfficialHabousCityDirectory directory,
            ApplicationHabousMapping mapping,
            SecApproachParameters parameters) {
        this.registry = registry;
        this.directory = directory;
        this.mapping = mapping;
        this.parameters = parameters;
    }

    /**
     * Verifies the nesting the whole model depends on. A resource set that breaks
     * it (e.g. offsets for a city with no verified mapping) would let the service
     * serve sec-approach times it cannot verify against official data, so it fails
     * startup instead.
     */
    @PostConstruct
    void verifyGroupInvariants() {
        for (String slug : parameters.supportedCitySlugs()) {
            if (mapping.findByApplicationCityId(slug).isEmpty()) {
                throw new IllegalStateException(
                        "Sec-approach city '" + slug + "' has no verified Habous mapping; "
                                + "the 48 sec-approach cities must be a subset of the 60 mapped cities");
            }
        }
        for (ApplicationHabousMapping.Mapping m : mapping.all()) {
            if (registry.findBySlug(m.applicationCityId()).isEmpty()) {
                throw new IllegalStateException(
                        "Mapped city '" + m.applicationCityId() + "' is not in the application registry; "
                                + "the 60 mapped cities must be a subset of the 67 registry cities");
            }
            if (!directory.isOfficialVille(m.habousCityId())) {
                throw new IllegalStateException(
                        "Mapping for '" + m.applicationCityId() + "' targets ville " + m.habousCityId()
                                + ", which is not in the official Habous directory");
            }
        }
        log.info(
                "City groups verified: {} official villes, {} registry cities, {} verified mappings, {} sec-approach cities",
                directory.count(), registry.count(), mapping.count(), parameters.count());
    }

    /**
     * @param cityIdentifier an application slug, name or alias, or an official ville id
     * @return what the service can do for it; never guesses
     */
    public CitySupport supportFor(String cityIdentifier) {
        Optional<City> city = registry.findByNameOrAlias(cityIdentifier);
        if (city.isEmpty()) {
            // Not an application city: it may still be an official ville we can collect.
            if (directory.isOfficialVille(cityIdentifier)) {
                Optional<ApplicationHabousMapping.Mapping> viaVille =
                        mapping.findByHabousCityId(cityIdentifier);
                if (viaVille.isPresent()) {
                    return supportFor(viaVille.get().applicationCityId());
                }
                return new CitySupport(
                        Optional.empty(),
                        SupportLevel.OFFICIAL_VILLE_UNMAPPED,
                        Optional.of(cityIdentifier.trim()),
                        false);
            }
            return new CitySupport(Optional.empty(), SupportLevel.UNKNOWN, Optional.empty(), false);
        }

        String slug = city.get().slug();
        Optional<ApplicationHabousMapping.Mapping> mapped = mapping.findByApplicationCityId(slug);
        if (mapped.isEmpty()) {
            return new CitySupport(Optional.of(slug), SupportLevel.REGISTRY_ONLY, Optional.empty(), false);
        }
        boolean secApproach = parameters.isSupported(slug);
        return new CitySupport(
                Optional.of(slug),
                secApproach ? SupportLevel.SEC_APPROACH_AND_OFFICIAL : SupportLevel.OFFICIAL_ONLY,
                Optional.of(mapped.get().habousCityId()),
                secApproach);
    }

    /** @return the 48 city slugs sec-approach can serve — never the 191 or the 67 */
    public List<String> secApproachCitySlugs() {
        return parameters.supportedCitySlugs().stream().sorted().toList();
    }

    /** @return the ville ids eligible for official collection (all 191) */
    public List<HabousVille> collectableVilles() {
        return directory.all();
    }
}
