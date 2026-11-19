package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.regex.Pattern;

/**
 * Which labelled groups are withheld for privacy (user's decision 2026-11-19): those whose title, field names or
 * details say they hold the VIN or an immobilizer / key identity. The VIN is never read or shown anywhere (root
 * {@code CLAUDE.md}), and immobilizer and key identities are the same kind of data.
 *
 * <p>A whole group is withheld if any of its labels match. Groups that only <i>mention</i> the immobilizer (CAN
 * communication status listing it as a node, key counts, transponder status) are not identities and stay
 * readable. Spec lines are not checked. Checked against all 1,964 decoded files on 2026-11-19: it catches every
 * "Vehicle Ident. Number (VIN)" (e.g. the target car's engine group 081, which also holds the IMMO-ID), every
 * "Immobilizer Ident. (IMMO-ID)", and the key identities ("Key-ID authorized", "Kessy Key Identity via CAN").
 */
final class PrivacyFilter {

    static final Pattern WITHHELD = Pattern.compile(
            "(?i)\\bVIN\\b|vehicle\\s*ident|chassis\\s*(no|num)|immobili[sz]er\\s*(ident|id\\b)"
                    + "|\\bimmo[-\\s]?(id|ident)|\\bWFS[-\\s]?(id|ident)|key[-\\s]*(id\\b|ident)|\\bidentity\\b");

    private PrivacyFilter() {
    }

    static boolean withholds(GroupLabel g) {
        if (WITHHELD.matcher(g.title()).find()) {
            return true;
        }
        for (FieldLabel f : g.fields()) {
            if (WITHHELD.matcher(f.name()).find() || WITHHELD.matcher(f.detail()).find()) {
                return true;
            }
        }
        return false;
    }
}
