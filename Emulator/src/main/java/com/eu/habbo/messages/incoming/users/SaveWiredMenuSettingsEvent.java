package com.eu.habbo.messages.incoming.users;

import com.eu.habbo.messages.incoming.MessageHandler;

/**
 * Official wired menu preferences (1226): the whisper switch in its own column, the rest of the
 * menu preferences as one stored text, sent back in the user settings.
 */
public class SaveWiredMenuSettingsEvent extends MessageHandler {
    @Override
    public void handle() throws Exception {
        UserPreferencePackets.WiredMenuPreferences preferences =
                UserPreferencePackets.parseWiredMenuPreferences(this.packet);

        this.client.getHabbo().getHabboStats().setWiredWhisperDisabled(preferences.wiredWhisperDisabled());
        this.client.getHabbo().getHabboStats().setWiredMenuPreferences(preferences.encode());
    }
}
