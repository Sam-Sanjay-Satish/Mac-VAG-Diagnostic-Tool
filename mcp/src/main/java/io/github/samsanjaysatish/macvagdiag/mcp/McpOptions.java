package io.github.samsanjaysatish.macvagdiag.mcp;

/**
 * App-startup settings for {@link McpEndpoint}. Set by the user in the app's config, never by the model: no tool
 * argument can change any of them.
 *
 * @param maskVin mask the VIN in every tool result (first 11 characters kept, the serial number becomes
 *     {@code *}; raw records that contain it are masked too). Default {@code true}.
 */
public record McpOptions(boolean maskVin) {

    /** VIN masked. */
    public static McpOptions defaults() {
        return new McpOptions(true);
    }

    public McpOptions withMaskVin(boolean value) {
        return new McpOptions(value);
    }
}
