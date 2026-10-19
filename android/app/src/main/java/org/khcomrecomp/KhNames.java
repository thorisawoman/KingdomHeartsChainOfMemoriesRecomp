package org.khcomrecomp;

/**
 * Sleight and room names for the bottom screen, from the pheenoh/khcom decompilation:
 * sleights in LearnedStock order (include/player_progression.h; bits 0-30 of learnedStocks,
 * then learnedStocks2 from bit 0), rooms in gRoomNames order (src/map/room_name.c, indexed by
 * MapFloorRoom.nameId).
 */
final class KhNames {
    private KhNames() {}

    static final String[] SLEIGHTS = {
        "Sliding Dash",
        "Blitz",
        "Stun Impact",
        "Zantetsuken",
        "Strike Raid",
        "Sonic Blade",
        "Ars Arcanum",
        "Ragnarok",
        "Trinity Limit",
        "Fira",
        "Firaga",
        "Blizzara",
        "Blizzaga",
        "Thundara",
        "Thundaga",
        "Cura",
        "Curaga",
        "Gravira",
        "Graviga",
        "Stopra",
        "Stopga",
        "Aerora",
        "Aeroga",
        "Fire Raid",
        "Blizzard Raid",
        "Thunder Raid",
        "Gravity Raid",
        "Stop Raid",
        "Judgment",
        "Reflect Raid",
        "Homing Fira",
        "Firaga Break",
        "Mega Flare",
        "Homing Blizzara",
        "Aqua Splash",
        "Shock Impact",
        "Tornado",
        "Quake",
        "Warpinator",
        "Warp",
        "Bind",
        "Confuse",
        "Terror",
        "Synchro",
        "Gifted Miracle",
        "Teleport",
        "Holy",
        "Proud Roar",
        "Splash",
        "Paradise",
        "Idyll Romp",
        "Flare Breath",
        "Showtime",
        "Twinkle",
        "Cross-slash",
        "Omnislash",
        "Cross-slash+",
        "Magic",
        "Blazing Donald",
        "Goofy Charge",
        "Goofy Tornado",
        "Sandstorm",
        "Surprise",
        "Spiral Wave",
        "Hummingbird",
        "Ferocious Lunge",
        "Dark Break",
        "Dark Firaga",
        "Dark Aura",
        "Mickey's Miracle",
        "",
        ""
    };

    static final String[] ROOMS = {
        "Teeming Darkness",
        "Tranquil Darkness",
        "Guarded Trove",
        "Looming Darkness",
        "Sleeping Darkness",
        "Moment's Reprieve",
        "Feeble Darkness",
        "Almighty Darkness",
        "Calm Bounty",
        "False Bounty",
        "Moogle Room",
        "Sorcerous Waking",
        "Martial Waking",
        "Alchemic Waking",
        "Meeting Ground",
        "Mingling Worlds",
        "Strong Initiative",
        "Lasting Daze",
        "Stagnant Space",
        "Premium Room",
        "White Room",
        "Black Room",
        "Key of Beginnings",
        "Key of Guidance",
        "Key to Truth",
        "Key to Rewards",
        "Unknown Place",
        "Hidden Chamber"
    };

    /** Learned sleight names from the four learnedStocks words (lo/hi, lo/hi). */
    static java.util.List<String> learnedSleights(int w0, int w1, int w2, int w3) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int s = 0; s < SLEIGHTS.length; s++) {
            boolean learned;
            if (s <= 30) learned = (w0 >>> s & 1) != 0;
            else {
                int b = s - 31;
                learned = ((b < 32 ? w2 >>> b : w3 >>> (b - 32)) & 1) != 0;
            }
            if (learned && !SLEIGHTS[s].isEmpty()) out.add(SLEIGHTS[s]);
        }
        return out;
    }

    static String room(int nameId) {
        return nameId >= 0 && nameId < ROOMS.length ? ROOMS[nameId] : "Unknown room";
    }
}
