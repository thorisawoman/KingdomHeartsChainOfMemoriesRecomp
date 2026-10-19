package org.khcomrecomp;

/**
 * Card names and categories for the bottom-screen deck view. Generated from the
 * pheenoh/khcom decompilation (include/card/card_ids.h): a card ID is
 * group * 10 + value for player cards (value 0-9), IDs from 450 are enemy cards.
 * Collection entries also carry flags: 0x8000 premium, 0x1000-0x4000 deck membership.
 */
final class KhCards {
    private KhCards() {}

    static final int ID_MASK = 0xFFF;
    static final int PREMIUM = 0x8000;
    static final int FIRST_ENEMY = 450;
    static final int FIRST_GIMMICK = 655;          // map gimmick cards 655-659
    static final int FIRST_KEYBLADE_EXTRA = 660;   // Soul Eater (66), The King (67)
    static final int FIRST_MAGIC_GROUP = 17;       // CARD_FIRE
    static final int FIRST_ITEM_GROUP = 38;        // CARD_POTION

    enum Category { ATTACK, MAGIC, ITEM, ENEMY, OTHER }

    private static final String[] GROUPS = {
        "Kingdom Key",
        "Three Wishes",
        "Crabclaw",
        "Pumpkinhead",
        "Fairy Harp",
        "Wishing Star",
        "Spellbinder",
        "Metal Chocobo",
        "Olympia",
        "Lionheart",
        "Lady Luck",
        "Divine Rose",
        "Oathkeeper",
        "Oblivion",
        "Diamond Dust",
        "One Winged Angel",
        "Ultima Weapon",
        "Fire",
        "Blizzard",
        "Thunder",
        "Cure",
        "Gravity",
        "Stop",
        "Aero",
        "Donald Duck",
        "Goofy",
        "Simba",
        "Genie",
        "Bambi",
        "Dumbo",
        "Tinker Bell",
        "Mushu",
        "Cloud",
        "Aladdin",
        "Ariel",
        "Jack",
        "Peter Pan",
        "The Beast",
        "Potion",
        "Hi-Potion",
        "Mega-Potion",
        "Ether",
        "Mega-Ether",
        "Elixir",
        "Megalixir",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "Soul Eater",
        "The King"
    };
    private static final int[] ENEMY_IDS = {453,456,457,458,459,460,461,462,463,464,465,466,467,468,469,470,477,480,483,484,485,489,495,498,499,500,501,504,507,510,513,514,515,516,517,518,519,522,523,524,525,526,527,528,532,533,534,535,536,537,538,539,540,541,542,543,544,545,548,551,552,553,554,555,556,557,558,559,560,561,562,563,564};
    private static final String[] ENEMY_NAMES = {
        "Soldier",
        "Large Body",
        "Large Body",
        "Large Body",
        "Red Nocturne",
        "Red Nocturne",
        "Red Nocturne",
        "Blue Rhapsody",
        "Blue Rhapsody",
        "Blue Rhapsody",
        "Yellow Opera",
        "Yellow Opera",
        "Yellow Opera",
        "Green Requiem",
        "Green Requiem",
        "Green Requiem",
        "Air Soldier",
        "Bandit",
        "Fat Bandit",
        "Fat Bandit",
        "Fat Bandit",
        "Search Ghost",
        "Screwdiver",
        "Aquatank",
        "Aquatank",
        "Aquatank",
        "Wight Knight",
        "Gargoyle",
        "Pirate",
        "Air Pirate",
        "Darkball",
        "Darkball",
        "Darkball",
        "Defender",
        "Defender",
        "Defender",
        "Wyvern",
        "Wizard",
        "Wizard",
        "Wizard",
        "Neoshadow",
        "Neoshadow",
        "Neoshadow",
        "White Mushroom",
        "Creeper Plant",
        "Creeper Plant",
        "Creeper Plant",
        "Tornado Step",
        "Tornado Step",
        "Tornado Step",
        "Crescendo",
        "Crescendo",
        "Crescendo",
        "Guard Armor",
        "Parasite Cage",
        "Trickmaster",
        "Darkside",
        "Card Soldier",
        "Card Soldier",
        "Hades",
        "Jafar",
        "Oogie Boogie",
        "Ursula",
        "Hook",
        "Dragon Maleficent",
        "Riku",
        "Axel",
        "Larxene",
        "Vexen",
        "Marluxia",
        "Marluxia",
        "Lexaeus",
        "Ansem"
    };

    static Category category(int id) {
        id &= ID_MASK;
        if (id >= FIRST_ENEMY && id < FIRST_GIMMICK) return Category.ENEMY;
        if (id >= FIRST_GIMMICK && id < FIRST_KEYBLADE_EXTRA) return Category.OTHER;
        int g = id / 10;
        if (g >= GROUPS.length) return Category.OTHER;
        if (g < FIRST_MAGIC_GROUP || g >= 66) return Category.ATTACK;
        if (g < FIRST_ITEM_GROUP) return Category.MAGIC;
        return Category.ITEM;
    }

    /** Group name ("Kingdom Key"), enemy name, or "#id" when unknown. */
    static String name(int id) {
        id &= ID_MASK;
        if (id >= FIRST_ENEMY && id < FIRST_GIMMICK) {
            int i = java.util.Arrays.binarySearch(ENEMY_IDS, id);
            return i >= 0 ? ENEMY_NAMES[i] : "Enemy #" + id;
        }
        int g = id / 10;
        if (id < FIRST_ENEMY || id >= FIRST_KEYBLADE_EXTRA) {
            if (g < GROUPS.length && !GROUPS[g].isEmpty()) return GROUPS[g];
        }
        return "#" + id;
    }

    /** Card value 0-9 for player cards, -1 for enemy cards. */
    static int value(int id) {
        id &= ID_MASK;
        return category(id) == Category.ENEMY ? -1 : id % 10;
    }
}
