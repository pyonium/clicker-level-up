package com.github.m0bilebtw;

import com.google.inject.Provides;

import java.time.Duration;
import java.util.HashMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import static com.github.m0bilebtw.Sound.BOND_OFFER_SOUNDS;
import static com.github.m0bilebtw.Sound.SNOWBALL_SOUNDS;
import static com.github.m0bilebtw.Sound.STAT_SPY_SOUNDS;
import static net.runelite.api.Varbits.DIARY_KARAMJA_EASY;
import static net.runelite.api.Varbits.DIARY_KARAMJA_HARD;
import static net.runelite.api.Varbits.DIARY_KARAMJA_MEDIUM;
import net.runelite.api.annotations.Varbit;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "C Engineer: Completed",
	description = "C Engineer announces when you complete an achievement",
	tags = {"c engineer", "stats", "levels", "quests", "diary", "announce"}
)

public class CEngineerCompletedPlugin extends Plugin
{
	@Inject
	private Client client;

	@Getter(AccessLevel.PACKAGE)
	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private SoundEngine soundEngine;

	@Inject
	private CEngineerCompletedConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OkHttpClient okHttpClient;

	private final int[] varbitsAchievementDiaries = {
			Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE,
			Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE,
			Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE,
			Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE,
			DIARY_KARAMJA_EASY, DIARY_KARAMJA_MEDIUM, DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE,
			Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE,
			Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE,
			Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE,
			Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE,
			Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE,
			Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE
	};

	// Killcount and new pb patterns from runelite/ChatCommandsPlugin
	private static final String ZULRAH = "Zulrah";
	private static final String C_ENGINEER = "C Engineer";
	private static final String SKILL_SPECS = "Skill Specs";
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?:completion count for |subdued |completed )?(.+?) (?:(?:kill|harvest|lap|completion) )?(?:count )?is: <col=ff0000>(\\d+)</col>");
	private static final Pattern NEW_PB_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in) <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
	private static final Pattern STRAY_DOG_GIVEN_BONES_REGEX = Pattern.compile("You give the dog some nice.*bones.*");
	private static final Pattern COLLECTION_LOG_ITEM_REGEX = Pattern.compile("New item added to your collection log:.*");
	private static final Pattern COMBAT_TASK_REGEX = Pattern.compile("Congratulations, you've completed an? (?:\\w+) combat task:.*");
	private static final Pattern QUEST_REGEX = Pattern.compile("Congratulations, you've completed a quest:.*");
	private static final Pattern BOND_OFFER_REGEX = Pattern.compile(C_ENGINEER + " is offering to give you a bond\\.");
	private static final Pattern STAT_SPY_REGEX = Pattern.compile(Text.standardize(C_ENGINEER + " is reading your skill stats!"));

	private static final Random random = new Random();

	private static final WorldArea FALADOR_HAIRDRESSER = new WorldArea(new WorldPoint(2942, 3377, 0), 8, 12);
	private static final int FALADOR_HAIRCUT_WIDGET_GROUP_ID = 516;
	private static final int CHILD_ID_MASK = 0xffff;

	private static final int ID_OBJECT_LUMCASTLE_GROUND_LEVEL_STAIRCASE = 16671;
    private static final int WORLD_POINT_LUMCASTLE_STAIRCASE_NORTH_X = 3204;
    private static final int WORLD_POINT_LUMCASTLE_STAIRCASE_NORTH_Y = 3229;

    private static final Set<Integer> badCollectionLogNotificationSettingValues = Set.of(0, 2);

	private final Map<Skill, Integer> oldExperience = new EnumMap<>(Skill.class);
	private final Map<Integer, Integer> oldAchievementDiaries = new HashMap<>();

	private int lastLoginTick = -1;
	private int lastGEOfferTick = -1;
	private int lastZulrahKillTick = -1;
	private int lastSnowballTriggerTick = -1;
	private int lastColLogSettingWarning = -1;

	private Player cEngineerPlayer = null;
	private boolean gameStateLoggedIn = false;

	@Override
	protected void startUp() throws Exception
	{
		clientThread.invoke(this::setupOldMaps);
		lastLoginTick = -1;
		executor.submit(() -> {
			SoundFileManager.ensureDownloadDirectoryExists();
			SoundFileManager.downloadAllMissingSounds(okHttpClient, config.downloadStreamerTrolls());
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		oldExperience.clear();
		oldAchievementDiaries.clear();
		soundEngine.close();
	}

	private void setupOldMaps() {
		if (client.getGameState() != GameState.LOGGED_IN) {
			oldExperience.clear();
			oldAchievementDiaries.clear();
		} else {
			for (final Skill skill : Skill.values()) {
				oldExperience.put(skill, client.getSkillExperience(skill));
			}
			for (@Varbit int diary : varbitsAchievementDiaries) {
				int value = client.getVarbitValue(diary);
				oldAchievementDiaries.put(diary, value);
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		gameStateLoggedIn = event.getGameState() == GameState.LOGGED_IN;
		switch(event.getGameState())
		{
			case LOGIN_SCREEN:
			case HOPPING:
			case LOGGING_IN:
			case LOGIN_SCREEN_AUTHENTICATOR:
				oldExperience.clear();
				oldAchievementDiaries.clear();
			case CONNECTION_LOST:
				// set to -1 here in-case of race condition with varbits changing before this handler is called
				// when game state becomes LOGGED_IN
				lastLoginTick = -1;
				lastColLogSettingWarning = client.getTickCount(); // avoid warning during DC
				break;
			case LOGGED_IN:
				lastLoginTick = client.getTickCount();
				break;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged) {
		final Skill skill = statChanged.getSkill();

		// Modified from Nightfirecat's virtual level ups plugin as this info isn't (yet?) built in to statChanged event
		final int xpAfter = client.getSkillExperience(skill);
		final int levelAfter = Experience.getLevelForXp(xpAfter);
		final int xpBefore = oldExperience.getOrDefault(skill, -1);
		final int levelBefore = xpBefore == -1 ? -1 : Experience.getLevelForXp(xpBefore);

		oldExperience.put(skill, xpAfter);

		// Do not proceed if any of the following are true:
		//  * xpBefore == -1              (don't fire when first setting new known value)
		//  * xpAfter <= xpBefore         (do not allow 200m -> 200m exp drops)
		//  * levelBefore >= levelAfter   (stop if if we're not actually reaching a new level)
		//  * levelAfter > MAX_REAL_LEVEL && config says don't include virtual (level is virtual and config ignores virtual)
		if (xpBefore == -1 || xpAfter <= xpBefore || levelBefore >= levelAfter ||
				(levelAfter > Experience.MAX_REAL_LEVEL && !config.announceLevelUpIncludesVirtual())) {
			return;
		}

		// If we get here, 'skill' was leveled up!
		if (config.announceLevelUp()) {
			if (config.showChatMessages()) {
				client.addChatMessage(ChatMessageType.PUBLICCHAT, C_ENGINEER, "Level up: completed.", null);
			}
			soundEngine.playClip(Sound.LEVEL_UP, executor);
		}
	}
}
