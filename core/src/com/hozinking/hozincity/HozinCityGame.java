package com.hozinking.hozincity;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.HashMap;
import java.util.Random;

/**
 * Hozin City v1.1.0 — mini open-world ala GTA SA (libGDX 3D).
 * v1.1: map 400x400 + peta fullscreen (tap minimap, dunia pause) + traffic
 * 8 mobil NPC + NPC 12 + tanah berbukit (jalan tetap datar) + 3 gedung bisa
 * dimasukin (TOKO/RUMAH/BENGKEL, interior di bawah map) + koin 15.
 * v1.0.3: arah setir DIPERBAIKI (dulu terbalik: joystick kanan = belok kiri;
 * bukti vektor di updateCar). Optimasi: geometri statis digabung jadi sedikit
 * mesh besar, satu directional light + ambient, blob shadow murah (tanpa
 * shadow map), fixed timestep 60 Hz, frustum culling manual untuk objek
 * dinamis, tanpa alokasi per-frame.
 */
public class HozinCityGame extends ApplicationAdapter implements InputProcessor {
    // ---------- konstanta kota ----------
    private static final float WORLD = 400f;          // tanah 400x400 (v1.1)
    private static final float HALF = WORLD / 2f;     // 200
    private static final float[] ROADS = {-160f, -120f, -80f, -40f, 0f, 40f, 80f, 120f, 160f};
    private static final float ROAD_W = 10f;
    // Bukit lembut: {cx, cz, radius, tinggi}. Hanya di blok taman/bukit —
    // radius < jarak ke jalan terdekat (20) sehingga JALAN tetap datar.
    private static final float[][] HILLS = {
            {20f, 20f, 13f, 3.0f},      // taman tengah
            {-180f, -180f, 14f, 4.5f},  // bukit pinggir barat-daya
            {140f, -140f, 12f, 2.5f},   // bukit timur
    };
    // Gedung yang bisa dimasukin: {cx, cz, tipe} — 0=TOKO, 1=RUMAH, 2=BENGKEL.
    private static final float[][] ENTER_SPOTS = {{20f, -20f, 0f}, {-20f, 20f, 1f}, {-20f, -20f, 2f}};
    private static final String[] ENTER_NAMES = {"TOKO", "RUMAH", "BENGKEL"};
    private static final float STEP = 1f / 60f;       // fixed timestep

    // ---------- render ----------
    private PerspectiveCamera cam;
    private ModelBatch modelBatch;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont font;
    private Environment env;
    private final Matrix4 ortho = new Matrix4();

    // ---------- aset ----------
    private final HashMap<String, Texture> texCache = new HashMap<>();
    private final Array<Model> models = new Array<>();
    private final Array<Texture> textures = new Array<>();

    // model statis (digabung)
    private ModelInstance groundInst, hazeInst, buildingsInst, treesInst, lampsInst;

    // ---------- pemain ----------
    private final Vector3 pPos = new Vector3(0, 0, 20);
    private float pHeading = 0f;          // radian, forward = (sin, 0, cos)
    private float pVelY = 0f;
    private boolean pOnGround = true;
    private float walkPhase = 0f;
    private ModelInstance pHead, pTorso, pArmL, pArmR, pLegL, pLegR;
    private ModelInstance pBlob;

    // ---------- mobil ----------
    private static class Car {
        final Vector3 pos = new Vector3();
        float heading, speed, steer, spin;
        ModelInstance body, cabin, blob;
        final ModelInstance[] wheels = new ModelInstance[4];
        Color color;
    }
    private final Array<Car> cars = new Array<>();
    private Car currentCar;
    private boolean driving;

    // ---------- NPC ----------
    private static class Npc {
        final Vector3 pos = new Vector3();
        float heading, speed, bobT;
        boolean alongX; // true = jalan searah X, false = searah Z
        float dir = 1f;
        ModelInstance body, head, blob;
    }
    private final Array<Npc> npcs = new Array<>();

    // ---------- koin ----------
    private static class Coin {
        final Vector3 pos = new Vector3();
        boolean taken;
        ModelInstance inst;
        float phase;
    }
    private final Array<Coin> coins = new Array<>();
    private int coinCount;
    private boolean won;

    // ---------- tabrakan gedung ----------
    private final Array<Rectangle> buildingBoxes = new Array<>();

    // ---------- traffic (mobil NPC, ambient — tidak bisa dimasukin) ----------
    private static class TrafficCar {
        final Vector3 pos = new Vector3();
        float heading, speed, targetSpeed;
        float tx, tz;       // titik target (persimpangan + offset lajur kanan)
        float ix, iz;       // persimpangan target (tanpa offset)
        float dirX, dirZ;   // arah jalan saat ini (sumbu grid)
        ModelInstance inst;
    }
    private final Array<TrafficCar> traffic = new Array<>();

    // ---------- gedung yang bisa dimasukin ----------
    private static class Enterable {
        String name;
        int type; // 0=TOKO, 1=RUMAH, 2=BENGKEL
        final Vector3 door = new Vector3(); // posisi di depan pintu (kota)
        float bx, bz; // pusat gedung
    }
    private final Array<Enterable> enterables = new Array<>();

    private static class Room {
        String name;
        ModelInstance inst;
        final Vector3 center = new Vector3(); // (rx, -60, 0)
        final Array<Rectangle> furn = new Array<>(); // kotak tabrakan furnitur
        final Vector3 exitDoor = new Vector3(); // posisi pintu keluar (di dalam)
    }
    private final Array<Room> rooms = new Array<>();
    private boolean inInterior;
    private int curRoom;
    private Enterable curEnterable;

    // ---------- peta fullscreen ----------
    private boolean mapOpen; // true = peta terbuka, dunia di-PAUSE
    private final Rectangle rMapClose = new Rectangle();

    // ---------- kamera (third-person ala GTA, v1.0.1) ----------
    // camYaw/camPitch/camDist = nilai TARGET (diatur drag/pinch).
    // camYawS/camPitchS/camDistS/camTargetS = nilai TERHALUS (yang dirender) —
    // di-lerp menuju target tiap frame supaya kamera tidak snap/kaku.
    // v1.0 TIDAK punya smoothing ini + auto-follow merampas kamera saat jalan
    // (itu sumber "rusak/aneh"); di v1.0.1 auto-follow HANYA saat nyetir kencang.
    private float camYaw = (float) Math.PI; // target yaw (di belakang pemain)
    private float camPitch = 0.42f;         // target pitch, rad
    private float camDist = 8f;             // target jarak
    private float camYawS = (float) Math.PI;
    private float camPitchS = 0.42f;
    private float camDistS = 8f;
    private float dragPause = 0f;
    private final Vector3 camTarget = new Vector3();
    private final Vector3 camTargetS = new Vector3(0, 1.2f, 20);

    // ---------- input ----------
    private int joyId = -1, camId = -1, pinchId = -1;
    private float joyX, joyY;             // -1..1 (joyY positif = ke atas layar)
    private float joyBaseX, joyBaseY;     // y-down
    private float camLastX, camLastY, pinchLastX, pinchLastY, pinchLastDist;
    private int gasId = -1, remId = -1;
    private boolean btnGas, btnRem;
    private boolean soundOn = true;
    private Preferences prefs;

    // tombol (y-down, dalam piksel layar)
    private final Rectangle rJump = new Rectangle();
    private final Rectangle rGas = new Rectangle();
    private final Rectangle rRem = new Rectangle();
    private final Rectangle rEnter = new Rectangle();
    private final Rectangle rSound = new Rectangle();
    private final Rectangle rReset = new Rectangle();

    // ---------- suara ----------
    private Sound humSnd, tapSnd, coinSnd, jumpSnd, jingleSnd;
    private long humId = -1;

    // ---------- misc ----------
    private float acc;
    private int fpsFrames, fpsValue;
    private float fpsTimer;
    private float time;
    private final Vector3 tmp = new Vector3();
    private final Vector3 tmp2 = new Vector3();
    private final Vector3 tmpN = new Vector3(); // normal grid tanah (per quad)
    private final Matrix4 tmpM = new Matrix4();
    private final Random rng = new Random(7);
    private String hudCoins = "KOIN: 0/10";
    private String hudFps = "FPS: --";
    private String hudSpeed = "";
    private boolean jumpQueued;
    private Model blobSmallM, blobMedM, blobBigM, wheelM, cabinM;

    @Override
    public void create() {
        Gdx.graphics.setForegroundFPS(60);
        prefs = Gdx.app.getPreferences("hozincity");
        soundOn = prefs.getBoolean("soundOn", true);

        cam = new PerspectiveCamera(67, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.near = 0.5f;
        cam.far = 900f;
        modelBatch = new ModelBatch();
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = new BitmapFont();
        font.getData().setScale(2f);

        // Siang terang ala GTA SA: langit biru muda, matahari hangat + ambient terang.
        env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.78f, 0.78f, 0.74f, 1f));
        env.add(new DirectionalLight().set(1.0f, 0.96f, 0.86f, -0.45f, -1f, -0.35f));

        SoundSynth.ensureSounds();
        try {
            humSnd = Gdx.audio.newSound(Gdx.files.local("sounds/motor_hum.wav"));
            tapSnd = Gdx.audio.newSound(Gdx.files.local("sounds/tap.wav"));
            coinSnd = Gdx.audio.newSound(Gdx.files.local("sounds/coin.wav"));
            jumpSnd = Gdx.audio.newSound(Gdx.files.local("sounds/jump.wav"));
            jingleSnd = Gdx.audio.newSound(Gdx.files.local("sounds/jingle.wav"));
        } catch (Exception ignored) {
            humSnd = tapSnd = coinSnd = jumpSnd = jingleSnd = null;
        }

        buildCity();
        buildPlayer();
        buildCars();
        buildTraffic();
        buildNpcs();
        buildCoins();
        buildInteriors();
        layoutButtons();
        Gdx.input.setInputProcessor(this);
        Gdx.input.setCatchBackKey(true); // tombol BACK menutup peta fullscreen
        updateHudText();
    }

    // ================= TEKSTUR & MATERIAL =================

    private Texture loadTex(String name) {
        Texture t = texCache.get(name);
        if (t != null) return t;
        try {
            t = new Texture(Gdx.files.internal("textures/" + name), true); // mipmaps
            t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        } catch (Exception e) {
            t = null;
        }
        if (t == null) return null;
        texCache.put(name, t);
        textures.add(t);
        return t;
    }

    private Material texMat(String texName, Color fallback) {
        Material m = new Material();
        Texture t = loadTex(texName);
        if (t != null) {
            m.set(TextureAttribute.createDiffuse(t));
        } else {
            m.set(ColorAttribute.createDiffuse(fallback));
        }
        return m;
    }

    private Material colorMat(float r, float g, float b) {
        Material m = new Material();
        m.set(ColorAttribute.createDiffuse(r, g, b, 1f));
        return m;
    }

    private Material shinyMat(float r, float g, float b, float spec, float shine) {
        Material m = new Material();
        m.set(ColorAttribute.createDiffuse(r, g, b, 1f));
        m.set(ColorAttribute.createSpecular(spec, spec, spec, 1f));
        m.set(FloatAttribute.createShininess(shine));
        return m;
    }

    private ModelInstance inst(Model m, float x, float y, float z) {
        ModelInstance mi = new ModelInstance(m);
        mi.transform.setToTranslation(x, y, z);
        return mi;
    }

    // ================= BUKIT (heightmap) =================

    /** Tinggi tanah di (x,z): 0 di jalan/trotoar, naik halus di zona bukit. */
    private static float heightAt(float x, float z) {
        float h = 0f;
        for (float[] b : HILLS) {
            float dx = x - b[0], dz = z - b[1];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < b[2]) h += b[3] * (0.5f + 0.5f * MathUtils.cos(MathUtils.PI * d / b[2]));
        }
        return h;
    }

    /** True bila blok berpusat di (cx,cz) berisi bukit (tanpa gedung/trotoar). */
    private static boolean isHillBlock(float cx, float cz) {
        for (float[] b : HILLS) {
            float dx = cx - b[0], dz = cz - b[1];
            if (dx * dx + dz * dz < 18f * 18f) return true;
        }
        return false;
    }

    // ================= KOTA (geometri statis digabung) =================

    private void buildCity() {
        ModelBuilder mb = new ModelBuilder();

        // --- tanah: grid ber-displace (bukit) + alas datar di bawahnya ---
        mb.begin();
        MeshPartBuilder base = mb.part("base", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(0.2f, 0.35f, 0.18f));
        base.box(0, -0.3f, 0, WORLD, 0.5f, WORLD);
        MeshPartBuilder grid = mb.part("grassGrid", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal | Usage.TextureCoordinates,
                texMat("tex-grass.jpg", new Color(0.35f, 0.6f, 0.3f, 1f)));
        int seg = 50;
        float cell = WORLD / seg; // 8 unit per quad
        for (int ix = 0; ix < seg; ix++) {
            for (int iz = 0; iz < seg; iz++) {
                float x0 = -HALF + ix * cell, x1 = x0 + cell;
                float z0 = -HALF + iz * cell, z1 = z0 + cell;
                float h00 = heightAt(x0, z0), h10 = heightAt(x1, z0);
                float h11 = heightAt(x1, z1), h01 = heightAt(x0, z1);
                float dhdx = ((h10 + h11) - (h00 + h01)) / (2f * cell);
                float dhdz = ((h01 + h11) - (h00 + h10)) / (2f * cell);
                tmpN.set(-dhdx, 1f, -dhdz).nor();
                grid.setUVRange(0, 0, 1, 1);
                // Urutan sudut CCW dilihat dari atas (normal +Y), sama seperti
                // sisi atas box() libGDX — kalau terbalik, quad tercull.
                grid.rect(x0, h01, z1, x1, h11, z1, x1, h10, z0, x0, h00, z0,
                        tmpN.x, tmpN.y, tmpN.z);
            }
        }
        MeshPartBuilder p = mb.part("asphalt", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal | Usage.TextureCoordinates,
                texMat("tex-asphalt.jpg", new Color(0.2f, 0.2f, 0.22f, 1f)));
        for (float c : ROADS) {
            p.setUVRange(0, 0, 40, 1);
            p.box(0, 0.02f, c, WORLD, 0.08f, ROAD_W);
            p.setUVRange(0, 0, 1, 40);
            p.box(c, 0.04f, 0, ROAD_W, 0.08f, WORLD);
        }
        p = mb.part("sidewalk", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal | Usage.TextureCoordinates,
                texMat("tex-sidewalk.jpg", new Color(0.7f, 0.7f, 0.68f, 1f)));
        for (int bx = 0; bx < 10; bx++) {
            for (int bz = 0; bz < 10; bz++) {
                float cx = -180f + bx * 40f, cz = -180f + bz * 40f;
                if (isHillBlock(cx, cz)) continue; // bukit dibiarkan terlihat
                p.setUVRange(0, 0, 3, 3);
                p.box(cx, 0f, cz, 30f, 0.06f, 30f);
            }
        }
        Model ground = mb.end();
        models.add(ground);
        groundInst = new ModelInstance(ground);

        // --- cincin haze: tanah luar memudar ke warna langit (pengganti fog shader) ---
        mb.begin();
        p = mb.part("haze", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal | Usage.TextureCoordinates | Usage.ColorPacked,
                texMat("tex-grass.jpg", new Color(0.5f, 0.7f, 0.55f, 1f)));
        // 4 sisi x 3 segmen: warna memudar putih -> langit
        float[][] segCol = {{1f, 1f, 1f}, {0.85f, 0.9f, 0.95f}, {0.62f, 0.78f, 0.95f}};
        for (int side = 0; side < 4; side++) {
            for (int s = 0; s < 3; s++) {
                float d0 = HALF + s * 110f, d1 = HALF + (s + 1) * 110f;
                p.setColor(segCol[s][0], segCol[s][1], segCol[s][2], 1f);
                p.setUVRange(0, 0, 6, 2);
                if (side == 0) p.box(0, -0.12f, -(d0 + d1) / 2f, 900f, 0.1f, d1 - d0); // utara
                else if (side == 1) p.box(0, -0.12f, (d0 + d1) / 2f, 900f, 0.1f, d1 - d0); // selatan
                else if (side == 2) p.box(-(d0 + d1) / 2f, -0.12f, 0, d1 - d0, 0.1f, 900f); // barat
                else p.box((d0 + d1) / 2f, -0.12f, 0, d1 - d0, 0.1f, 900f); // timur
            }
        }
        Model haze = mb.end();
        models.add(haze);
        hazeInst = new ModelInstance(haze);

        // --- gedung: 4 part (fasad + atap + pintu + papan nama) dalam 1 model ---
        mb.begin();
        p = mb.part("facade", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal | Usage.TextureCoordinates,
                texMat("tex-facade.jpg", new Color(0.75f, 0.68f, 0.55f, 1f)));
        MeshPartBuilder roof = mb.part("roof", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal,
                colorMat(0.16f, 0.17f, 0.2f));
        MeshPartBuilder doorP = mb.part("door", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(0.45f, 0.28f, 0.12f));
        MeshPartBuilder signP = mb.part("sign", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(0.95f, 0.92f, 0.8f));
        for (int bx = 0; bx < 10; bx++) {
            for (int bz = 0; bz < 10; bz++) {
                float cx = -180f + bx * 40f, cz = -180f + bz * 40f;
                if (isHillBlock(cx, cz)) continue; // blok bukit: tanpa gedung
                float distC = (float) Math.sqrt(cx * cx + cz * cz);
                float hBase = 8f + (1f - Math.min(distC / 260f, 1f)) * 20f;
                int entIdx = -1;
                for (int e = 0; e < ENTER_SPOTS.length; e++) {
                    if (ENTER_SPOTS[e][0] == cx && ENTER_SPOTS[e][1] == cz) entIdx = e;
                }
                if ((bx + bz) % 2 == 0) {
                    // 1 gedung besar + 1 annex kecil di sudut blok
                    float w = 13f + rng.nextFloat() * 2f, d = 13f + rng.nextFloat() * 2f;
                    float h = hBase + rng.nextFloat() * 6f;
                    addBuilding(p, roof, cx, cz, w, h, d);
                    if (entIdx >= 0) addDoor(doorP, signP, entIdx, cx, cz, w, d);
                    addBuilding(p, roof, cx - 11f, cz - 11f, 6f, 5f + rng.nextFloat() * 2f, 6f);
                } else {
                    // 2 gedung kecil berdampingan (gedung pertama bisa dimasukin)
                    for (int k = 0; k < 2; k++) {
                        float bx2 = cx + (k == 0 ? -7.5f : 7.5f);
                        float w = 8f + rng.nextFloat() * 3f, d = 12f + rng.nextFloat() * 4f;
                        float h = hBase * 0.7f + rng.nextFloat() * 5f;
                        addBuilding(p, roof, bx2, cz, w, h, d);
                        if (entIdx >= 0 && k == 0) addDoor(doorP, signP, entIdx, bx2, cz, w, d);
                    }
                }
            }
        }
        Model buildings = mb.end();
        models.add(buildings);
        buildingsInst = new ModelInstance(buildings);

        // --- pohon: 2 part (batang + tajuk) ---
        mb.begin();
        p = mb.part("trunk", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.35f, 0.22f, 0.12f));
        MeshPartBuilder canopy = mb.part("canopy", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(0.2f, 0.5f, 0.2f));
        // taman (20,20): 6 pohon di atas bukit taman
        for (int i = 0; i < 6; i++) {
            float x = 8f + rng.nextFloat() * 24f, z = 8f + rng.nextFloat() * 24f;
            addTree(p, canopy, x, z, heightAt(x, z));
        }
        // bukit pinggir: 4 pohon
        float[][] hillTrees = {{-188f, -172f}, {-172f, -188f}, {-186f, -186f}, {-174f, -174f},
                {132f, -148f}, {148f, -132f}, {140f, -148f}};
        for (float[] t : hillTrees) addTree(p, canopy, t[0], t[1], heightAt(t[0], t[1]));
        // pinggir trotoar: 8 pohon
        for (int i = 0; i < 8; i++) {
            float x = -190f + rng.nextFloat() * 380f;
            float z = ROADS[rng.nextInt(ROADS.length)] + (rng.nextBoolean() ? 8f : -8f);
            addTree(p, canopy, x, z, 0f);
        }
        Model trees = mb.end();
        models.add(trees);
        treesInst = new ModelInstance(trees);

        // --- lampu jalan (model terpisah, posisi lewat box) ---
        buildLamps();
    }

    private void addBuilding(MeshPartBuilder facade, MeshPartBuilder roof,
                             float cx, float cz, float w, float h, float d) {
        facade.setUVRange(0, 0, Math.max(1f, w / 6f), Math.max(1f, h / 6f));
        facade.box(cx, h / 2f, cz, w, h, d);
        roof.box(cx, h + 0.15f, cz, w + 0.2f, 0.3f, d + 0.2f);
        buildingBoxes.add(new Rectangle(cx - w / 2f, cz - d / 2f, w, d));
    }

    private void addTree(MeshPartBuilder trunk, MeshPartBuilder canopy,
                         float x, float z, float baseY) {
        trunk.box(x, baseY + 1.1f, z, 0.5f, 2.2f, 0.5f);
        canopy.sphere(tmpM.setToTranslation(x, baseY + 3.6f, z), 3.2f, 3.2f, 3.2f, 10, 8);
    }

    /** Pintu + papan nama di fasad +Z gedung yang bisa dimasukin. */
    private void addDoor(MeshPartBuilder doorP, MeshPartBuilder signP,
                         int entIdx, float cx, float cz, float w, float d) {
        doorP.box(cx, 1.2f, cz + d / 2f + 0.1f, 1.8f, 2.4f, 0.25f);
        signP.box(cx, 2.9f, cz + d / 2f + 0.12f, 3.2f, 0.9f, 0.15f);
        Enterable en = new Enterable();
        en.name = ENTER_NAMES[entIdx];
        en.type = (int) ENTER_SPOTS[entIdx][2];
        en.bx = cx;
        en.bz = cz;
        en.door.set(cx, 0f, cz + d / 2f + 1.0f);
        enterables.add(en);
    }

    private void buildLamps() {
        // Lampu dibangun sebagai model terpisah dengan node transform (posisi benar).
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder p = mb.part("pole", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(0.15f, 0.15f, 0.17f));
        MeshPartBuilder head = mb.part("head", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, colorMat(1f, 0.9f, 0.5f));
        for (int i = 0; i < ROADS.length; i++) {
            for (int j = 0; j < ROADS.length; j++) {
                if ((i + j) % 2 != 0) continue;
                float x = ROADS[i] + 6.5f, z = ROADS[j] + 6.5f;
                p.box(x, 2.5f, z, 0.24f, 5f, 0.24f);
                head.box(x, 5.15f, z, 0.7f, 0.3f, 0.7f);
            }
        }
        Model lamps = mb.end();
        models.add(lamps);
        lampsInst = new ModelInstance(lamps);
    }

    // ================= BLOB SHADOW (bayangan murah, tanpa shadow map) =================

    private Model makeBlob(float radius) {
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        Material m = new Material();
        m.set(ColorAttribute.createDiffuse(0f, 0f, 0f, 1f));
        m.set(new BlendingAttribute(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.35f));
        MeshPartBuilder p = mb.part("blob", GL20.GL_TRIANGLES,
                Usage.Position | Usage.Normal, m);
        p.cylinder(radius * 2f, 0.02f, radius * 2f, 16);
        Model model = mb.end();
        models.add(model);
        return model;
    }

    // ================= PEMAIN =================

    private void buildPlayer() {
        blobMedM = makeBlob(0.9f);
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder p = mb.part("head", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.85f, 0.65f, 0.45f));
        p.box(0, 1.75f, 0, 0.5f, 0.5f, 0.5f);
        Model headM = mb.end();
        models.add(headM);
        pHead = new ModelInstance(headM);

        mb.begin();
        p = mb.part("torso", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.15f, 0.35f, 0.75f));
        p.box(0, 1.05f, 0, 0.8f, 0.9f, 0.45f);
        Model torsoM = mb.end();
        models.add(torsoM);
        pTorso = new ModelInstance(torsoM);

        mb.begin();
        p = mb.part("arm", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.15f, 0.35f, 0.75f));
        p.box(0, -0.425f, 0, 0.22f, 0.85f, 0.22f);
        Model armM = mb.end();
        models.add(armM);
        pArmL = new ModelInstance(armM);
        pArmR = new ModelInstance(armM);

        mb.begin();
        p = mb.part("leg", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.15f, 0.15f, 0.2f));
        p.box(0, -0.45f, 0, 0.28f, 0.9f, 0.28f);
        Model legM = mb.end();
        models.add(legM);
        pLegL = new ModelInstance(legM);
        pLegR = new ModelInstance(legM);

        pBlob = new ModelInstance(blobMedM);
    }

    // ================= MOBIL =================

    private void buildCars() {
        blobBigM = makeBlob(1.7f);
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder p = mb.part("cabin", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                shinyMat(0.1f, 0.12f, 0.16f, 0.9f, 64f));
        p.box(0, 1.2f, -0.2f, 1.6f, 0.6f, 1.8f);
        cabinM = mb.end();
        models.add(cabinM);

        mb.begin();
        p = mb.part("wheel", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.08f, 0.08f, 0.09f));
        p.cylinder(0.84f, 0.3f, 0.84f, 12);
        wheelM = mb.end();
        models.add(wheelM);

        float[][] defs = {
                {0f, -20f, 0f, 0.85f, 0.15f, 0.15f},       // merah
                {40f, 60f, 90f, 0.95f, 0.8f, 0.15f},      // kuning
                {-40f, -60f, -90f, 0.15f, 0.35f, 0.85f},  // biru
                {80f, 20f, 180f, 0.9f, 0.9f, 0.88f},      // putih
        };
        for (float[] d : defs) {
            Car c = new Car();
            c.pos.set(d[0], 0, d[1]);
            c.heading = d[2] * MathUtils.degreesToRadians;
            c.color = new Color(d[3], d[4], d[5], 1f);
            mb.begin();
            p = mb.part("body", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                    shinyMat(d[3], d[4], d[5], 0.9f, 64f));
            p.box(0, 0.55f, 0, 1.8f, 0.7f, 3.6f);
            Model bodyM = mb.end();
            models.add(bodyM);
            c.body = new ModelInstance(bodyM);
            c.cabin = new ModelInstance(cabinM);
            for (int i = 0; i < 4; i++) c.wheels[i] = new ModelInstance(wheelM);
            c.blob = new ModelInstance(blobBigM);
            cars.add(c);
        }
    }

    // ================= TRAFFIC (mobil NPC, ambient) =================

    private void buildTraffic() {
        // Warna beda dari 4 mobil parkir (merah/kuning/biru/putih).
        float[][] defs = {
                {0.55f, 0.25f, 0.75f}, {0.95f, 0.5f, 0.1f}, {0.1f, 0.7f, 0.7f},
                {0.9f, 0.3f, 0.6f}, {0.5f, 0.85f, 0.2f}, {0.45f, 0.45f, 0.5f},
                {0.6f, 0.2f, 0.2f}, {0.15f, 0.8f, 0.9f},
        };
        ModelBuilder mb = new ModelBuilder();
        for (float[] d : defs) {
            // 1 model, 3 part: bodi + kabin + 4 roda kotak (statis, tanpa putar).
            mb.begin();
            MeshPartBuilder p = mb.part("tbody", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, shinyMat(d[0], d[1], d[2], 0.9f, 64f));
            p.box(0, 0.55f, 0, 1.8f, 0.7f, 3.6f);
            p = mb.part("tcabin", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                    shinyMat(0.1f, 0.12f, 0.16f, 0.9f, 64f));
            p.box(0, 1.2f, -0.2f, 1.6f, 0.6f, 1.8f);
            p = mb.part("twheels", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                    colorMat(0.08f, 0.08f, 0.09f));
            p.box(0.85f, 0.42f, 1.15f, 0.3f, 0.84f, 0.84f);
            p.box(-0.85f, 0.42f, 1.15f, 0.3f, 0.84f, 0.84f);
            p.box(0.85f, 0.42f, -1.15f, 0.3f, 0.84f, 0.84f);
            p.box(-0.85f, 0.42f, -1.15f, 0.3f, 0.84f, 0.84f);
            Model m = mb.end();
            models.add(m);
            TrafficCar t = new TrafficCar();
            t.inst = new ModelInstance(m);
            t.targetSpeed = 8f + rng.nextFloat() * 5f;
            // Start: persimpangan acak, sudah di lajur kanan.
            t.ix = ROADS[rng.nextInt(ROADS.length)];
            t.iz = ROADS[rng.nextInt(ROADS.length)];
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            int[] dir = dirs[rng.nextInt(4)];
            t.dirX = dir[0];
            t.dirZ = dir[1];
            t.heading = (float) Math.atan2(t.dirX, t.dirZ);
            // lajur kanan: right = (-dirZ, dirX) * 2.5
            t.pos.set(t.ix - t.dirZ * 2.5f, 0, t.iz + t.dirX * 2.5f);
            pickNextTarget(t);
            traffic.add(t);
        }
    }

    /** Pilih persimpangan berikut (tetap di grid jalan) + target di lajur kanan. */
    private void pickNextTarget(TrafficCar t) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] w = {1, 1, 1, 1};
        for (int i = 0; i < 4; i++) {
            if (dirs[i][0] == (int) t.dirX && dirs[i][1] == (int) t.dirZ) w[i] = 3; // lurus 3x
        }
        int pick = -1;
        for (int tries = 0; tries < 12 && pick < 0; tries++) {
            int total = w[0] + w[1] + w[2] + w[3];
            int r = rng.nextInt(total), acc = 0;
            for (int i = 0; i < 4; i++) {
                acc += w[i];
                if (r < acc) {
                    float nx = t.ix + dirs[i][0] * 40f, nz = t.iz + dirs[i][1] * 40f;
                    if (nx >= -160f && nx <= 160f && nz >= -160f && nz <= 160f) pick = i;
                    break;
                }
            }
        }
        if (pick < 0) { // mentok di sudut: putar balik
            for (int i = 0; i < 4; i++) {
                if (dirs[i][0] == -(int) t.dirX && dirs[i][1] == -(int) t.dirZ) pick = i;
            }
        }
        t.dirX = dirs[pick][0];
        t.dirZ = dirs[pick][1];
        t.ix += t.dirX * 40f;
        t.iz += t.dirZ * 40f;
        t.tx = t.ix - t.dirZ * 2.5f;
        t.tz = t.iz + t.dirX * 2.5f;
    }

    // ================= PEJALAN KAKI =================

    private void buildNpcs() {
        blobSmallM = makeBlob(0.6f);
        float[][] shirts = {
                {0.2f, 0.7f, 0.3f}, {0.9f, 0.5f, 0.15f}, {0.6f, 0.3f, 0.8f},
                {0.9f, 0.4f, 0.55f}, {0.5f, 0.5f, 0.5f}, {0.15f, 0.6f, 0.65f},
                {0.3f, 0.3f, 0.9f}, {0.9f, 0.9f, 0.3f}, {0.4f, 0.8f, 0.9f},
                {0.8f, 0.4f, 0.2f}, {0.5f, 0.9f, 0.5f}, {0.7f, 0.7f, 0.9f},
        };
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder p = mb.part("head", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                colorMat(0.85f, 0.65f, 0.45f));
        p.box(0, 1.72f, 0, 0.42f, 0.42f, 0.42f);
        Model headM = mb.end();
        models.add(headM);
        for (int i = 0; i < 12; i++) {
            Npc n = new Npc();
            float road = ROADS[rng.nextInt(ROADS.length)];
            n.alongX = rng.nextBoolean();
            n.dir = rng.nextBoolean() ? 1f : -1f;
            n.speed = 1.2f + rng.nextFloat() * 0.8f;
            if (n.alongX) {
                n.pos.set(-190f + rng.nextFloat() * 380f, 0, road + (rng.nextBoolean() ? 7.5f : -7.5f));
                n.heading = n.dir > 0 ? MathUtils.PI / 2f : -MathUtils.PI / 2f;
            } else {
                n.pos.set(road + (rng.nextBoolean() ? 7.5f : -7.5f), 0, -190f + rng.nextFloat() * 380f);
                n.heading = n.dir > 0 ? 0f : MathUtils.PI;
            }
            n.pos.y = heightAt(n.pos.x, n.pos.z);
            mb.begin();
            p = mb.part("body", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                    colorMat(shirts[i][0], shirts[i][1], shirts[i][2]));
            p.box(0, 1.0f, 0, 0.7f, 1.0f, 0.4f);
            Model bodyM = mb.end();
            models.add(bodyM);
            n.body = new ModelInstance(bodyM);
            n.head = new ModelInstance(headM);
            n.blob = new ModelInstance(blobSmallM);
            npcs.add(n);
        }
    }

    // ================= KOIN =================

    private void buildCoins() {
        // 15 koin, semua di jalan (gampang dijangkau mobil) kecuali 1 di taman.
        float[][] spots = {
                {0f, 0f}, {40f, 20f}, {-40f, -60f}, {80f, 40f}, {-80f, 0f},
                {20f, 80f}, {-20f, -80f}, {60f, -40f}, {-60f, 40f}, {20f, 20f},
                {120f, -100f}, {-140f, 80f}, {160f, 140f}, {-160f, -120f}, {100f, 160f},
        };
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder p = mb.part("coin", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                shinyMat(1f, 0.8f, 0.2f, 1f, 64f));
        p.cylinder(1.1f, 0.12f, 1.1f, 14);
        Model coinM = mb.end();
        models.add(coinM);
        for (int i = 0; i < spots.length; i++) {
            Coin c = new Coin();
            c.pos.set(spots[i][0], heightAt(spots[i][0], spots[i][1]), spots[i][1]);
            c.phase = rng.nextFloat() * 6f;
            c.inst = new ModelInstance(coinM);
            coins.add(c);
        }
    }

    // ================= INTERIOR GEDUNG (di bawah map, y=-60) =================

    private void buildInteriors() {
        // Tiap ruangan: lantai + langit-langit + 4 dinding + furnitur khas.
        // Ruangan tipe i berpusat di (i*30, -60, 0); hanya ruangan yang sedang
        // dimasukin yang di-render (di bawah tanah, tak terlihat dari kota).
        for (int type = 0; type < 3; type++) {
            float rx = type * 30f;
            Room rm = new Room();
            rm.name = ENTER_NAMES[type];
            rm.center.set(rx, -60f, 0f);
            rm.exitDoor.set(rx, -60f, -3.0f);
            ModelBuilder mb = new ModelBuilder();
            mb.begin();
            float[] wc = type == 0 ? new float[]{0.6f, 0.8f, 0.6f}
                    : type == 1 ? new float[]{0.9f, 0.85f, 0.7f}
                    : new float[]{0.6f, 0.6f, 0.62f};
            MeshPartBuilder wall = mb.part("wall", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, colorMat(wc[0], wc[1], wc[2]));
            wall.box(rx, -58f, -4f, 12f, 4f, 0.4f);   // utara
            wall.box(rx, -58f, 4f, 12f, 4f, 0.4f);    // selatan
            wall.box(rx - 6f, -58f, 0, 0.4f, 4f, 8f); // barat
            wall.box(rx + 6f, -58f, 0, 0.4f, 4f, 8f); // timur
            float[] fc = type == 2 ? new float[]{0.5f, 0.5f, 0.5f}
                    : new float[]{0.55f, 0.4f, 0.25f};
            MeshPartBuilder floor = mb.part("floor", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, colorMat(fc[0], fc[1], fc[2]));
            floor.box(rx, -60.25f, 0, 12f, 0.5f, 8f); // lantai (atas di -60)
            floor.box(rx, -55.75f, 0, 12f, 0.5f, 8f); // langit-langit (bawah di -56)
            MeshPartBuilder door = mb.part("door", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, colorMat(0.45f, 0.28f, 0.12f));
            door.box(rx, -59f, -3.75f, 1.6f, 2.2f, 0.15f); // pintu keluar (visual)
            MeshPartBuilder furn = mb.part("furn", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, colorMat(0.5f, 0.35f, 0.2f));
            MeshPartBuilder furn2 = mb.part("furn2", GL20.GL_TRIANGLES,
                    Usage.Position | Usage.Normal, colorMat(0.15f, 0.15f, 0.18f));
            if (type == 0) { // TOKO: 3 rak + meja kasir
                for (int k = -1; k <= 1; k++) {
                    furn.box(rx + k * 4f, -59f, -3.0f, 2.5f, 2f, 0.8f);
                    rm.furn.add(new Rectangle(rx + k * 4f - 1.25f, -3.4f, 2.5f, 0.8f));
                }
                furn.box(rx + 3.5f, -59.5f, 1.5f, 2.5f, 1f, 1f);
                rm.furn.add(new Rectangle(rx + 3.5f - 1.25f, 1f, 2.5f, 1f));
            } else if (type == 1) { // RUMAH: tempat tidur + meja + kursi
                furn.box(rx - 3.5f, -59.7f, -2f, 2.2f, 0.6f, 3.5f);
                rm.furn.add(new Rectangle(rx - 3.5f - 1.1f, -3.75f, 2.2f, 3.5f));
                furn.box(rx - 3.5f, -59.5f, -3.4f, 2.2f, 0.4f, 0.4f); // bantal
                furn2.box(rx + 2f, -59.6f, 0.5f, 2f, 0.8f, 1.2f);
                rm.furn.add(new Rectangle(rx + 2f - 1f, -0.1f, 2f, 1.2f));
                furn2.box(rx + 2f, -59.8f, 2.2f, 0.8f, 0.5f, 0.8f);
            } else { // BENGKEL: meja kerja + tumpukan ban + perkakas
                furn.box(rx - 3f, -59.5f, -2.5f, 3f, 1f, 1.2f);
                rm.furn.add(new Rectangle(rx - 3f - 1.5f, -3.1f, 3f, 1.2f));
                furn2.box(rx + 3f, -59.8f, 1f, 1f, 0.35f, 1f);
                furn2.box(rx + 3f, -59.45f, 1f, 1f, 0.35f, 1f);
                furn.box(rx - 3.5f, -58.9f, -2.5f, 0.5f, 0.2f, 0.5f);
                furn.box(rx - 2.5f, -58.9f, -2.3f, 0.4f, 0.2f, 0.4f);
            }
            Model m = mb.end();
            models.add(m);
            rm.inst = new ModelInstance(m);
            rooms.add(rm);
        }
    }

    private void layoutButtons() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        // v1.0.2: tombol SUARA dilebarkan (label "SUARA: OFF" skala font 2
        // meluber keluar kotak 150px dan menumpuk label RESET — terlihat di
        // screenshot user sebagai "SUARA: ONRESET"); RESET digeser ke kanan
        // dengan jarak jelas.
        rSound.set(16, 16, 230, 60);
        rReset.set(256, 16, 130, 60);
        rGas.set(w - 240, h - 170, 200, 130);
        rRem.set(w - 460, h - 170, 190, 130);
        rJump.set(w - 240, h - 330, 200, 110);
        rEnter.set(w - 460, h - 330, 190, 110);
        rMapClose.set(w / 2f - 100, h - 70, 200, 60);
        joyBaseX = 120;
        joyBaseY = h - 140;
    }

    // ================= UPDATE (fixed timestep) =================

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        acc += dt;
        int steps = 0;
        while (acc >= STEP && steps < 4) {
            update(STEP);
            acc -= STEP;
            steps++;
        }
        renderScene();
        fpsFrames++;
        fpsTimer += dt;
        if (fpsTimer >= 0.5f) {
            fpsValue = (int) (fpsFrames / fpsTimer);
            fpsFrames = 0;
            fpsTimer = 0;
            updateHudText();
        }
    }

    private void update(float dt) {
        if (mapOpen) return; // peta fullscreen: dunia di-PAUSE
        time += dt;
        dragPause = Math.max(0f, dragPause - dt);
        float joyLen = (float) Math.sqrt(joyX * joyX + joyY * joyY);
        if (joyLen > 1f) {
            joyX /= joyLen;
            joyY /= joyLen;
            joyLen = 1f;
        }

        if (driving && currentCar != null) updateCar(dt, joyLen);
        else updatePlayer(dt, joyLen);

        updateNpcs(dt);
        updateTraffic(dt);
        updateCoins(dt);
        updateCamera(dt);
        updateHum();
    }

    private void updatePlayer(float dt, float joyLen) {
        float groundH = inInterior ? -60f : heightAt(pPos.x, pPos.z);
        if (joyLen > 0.08f) {
            // Gerak relatif terhadap arah kamera YANG TERLIHAT (yaw terhalus),
            // bukan yaw target — supaya arah joystick selalu cocok dengan layar.
            float fx = -MathUtils.sin(camYawS), fz = -MathUtils.cos(camYawS);
            float rx = MathUtils.cos(camYawS), rz = -MathUtils.sin(camYawS);
            float mx = rx * joyX + fx * joyY;
            float mz = rz * joyX + fz * joyY;
            float len = (float) Math.sqrt(mx * mx + mz * mz);
            if (len > 0.01f) {
                mx /= len;
                mz /= len;
                float speed = joyLen * 10f;
                pPos.x += mx * speed * dt;
                pPos.z += mz * speed * dt;
                float targetH = (float) Math.atan2(mx, mz);
                pHeading = lerpAngle(pHeading, targetH, 1f - (float) Math.exp(-12f * dt));
                walkPhase += dt * speed * 1.5f;
            }
            if (inInterior) {
                collideCircle(pPos, 0.5f, false, rooms.get(curRoom).furn);
            } else {
                collideCircle(pPos, 0.5f, false, buildingBoxes);
            }
        }
        // lompat + gravitasi (lantai = tinggi tanah setempat / lantai interior)
        if (jumpQueued) {
            jumpQueued = false;
            if (pOnGround) {
                pVelY = 8.5f;
                pOnGround = false;
                if (soundOn && jumpSnd != null) jumpSnd.play(0.8f);
            }
        }
        if (!pOnGround) {
            pVelY -= 22f * dt;
            pPos.y += pVelY * dt;
            if (pPos.y <= groundH) {
                pPos.y = groundH;
                pVelY = 0f;
                pOnGround = true;
            }
        } else {
            pPos.y = groundH; // jalan kaki mengikuti tinggi tanah (naik bukit)
        }
        if (inInterior) {
            Room rm = rooms.get(curRoom);
            pPos.x = MathUtils.clamp(pPos.x, rm.center.x - 5.2f, rm.center.x + 5.2f);
            pPos.z = MathUtils.clamp(pPos.z, -3.2f, 3.2f);
        } else {
            pPos.x = MathUtils.clamp(pPos.x, -HALF + 2f, HALF - 2f);
            pPos.z = MathUtils.clamp(pPos.z, -HALF + 2f, HALF - 2f);
        }
    }

    private void updateCar(float dt, float joyLen) {
        Car c = currentCar;
        float maxSpeed = 28f;
        if (btnGas) {
            c.speed += 14f * dt;
            if (c.speed > maxSpeed) c.speed = maxSpeed;
        } else if (btnRem) {
            if (c.speed > 0.5f) c.speed -= 30f * dt;
            else {
                c.speed -= 6f * dt;
                if (c.speed < -8f) c.speed = -8f;
            }
        } else {
            c.speed -= c.speed * 0.6f * dt;
            if (Math.abs(c.speed) < 0.05f) c.speed = 0f;
        }
        // TANDA SETIR (v1.0.3 — BUG: dulu positif, TERBALIK di layar).
        // Bukti: forward mobil F=(sin h, 0, cos h); kamera auto-center di
        // belakang mobil (camYaw = h+pi) sehingga screen-right R=(-cos h, 0, sin h).
        // heading += speed/2.9*tan(steer)*dt -> dF/dh=(cos h, 0, -sin h);
        // dF . R = -(cos^2+sin^2) = -1 < 0 untuk steer>0 (joyX>0): mobil
        // belok ke KIRI layar padahal joystick ke kanan. Saat mundur (speed<0)
        // tandanya ikut terbalik (mundur harusnya ke kanan malah ke kiri).
        // Perbaikan: negasikan steerTarget di sini (BUKAN heading) supaya
        // visual roda depan (rotate Y sebesar steer) ikut benar arahnya.
        float steerTarget = -joyX * 0.55f * (1f - Math.min(Math.abs(c.speed) / maxSpeed, 1f) * 0.55f);
        c.steer += (steerTarget - c.steer) * Math.min(1f, 8f * dt);
        c.heading += c.speed / 2.9f * (float) Math.tan(c.steer) * dt;
        float fx = MathUtils.sin(c.heading), fz = MathUtils.cos(c.heading);
        c.pos.x += fx * c.speed * dt;
        c.pos.z += fz * c.speed * dt;
        c.spin += c.speed / 0.42f * dt;
        if (collideCircle(c.pos, 1.7f, true, buildingBoxes)) c.speed *= 0.35f;
        c.pos.x = MathUtils.clamp(c.pos.x, -HALF + 2f, HALF - 2f);
        c.pos.z = MathUtils.clamp(c.pos.z, -HALF + 2f, HALF - 2f);
        c.pos.y = heightAt(c.pos.x, c.pos.z); // mobil mengikuti tanah (0 di jalan)
        hudSpeed = "KECEPATAN: " + (int) (Math.abs(c.speed) * 3.6f) + " km/jam";
    }

    /** Tabrakan lingkaran vs AABB; dorong keluar, kembalikan true bila menabrak. */
    private boolean collideCircle(Vector3 pos, float r, boolean car, Array<Rectangle> boxes) {
        boolean hit = false;
        for (Rectangle b : boxes) {
            float cx = MathUtils.clamp(pos.x, b.x, b.x + b.width);
            float cz = MathUtils.clamp(pos.z, b.y, b.y + b.height);
            float dx = pos.x - cx, dz = pos.z - cz;
            float d2 = dx * dx + dz * dz;
            if (d2 < r * r) {
                hit = true;
                if (d2 < 0.0001f) {
                    // pusat di dalam kotak: dorong ke sisi terdekat
                    float l = pos.x - b.x, ri = b.x + b.width - pos.x;
                    float t = pos.z - b.y, bo = b.y + b.height - pos.z;
                    float m = Math.min(Math.min(l, ri), Math.min(t, bo));
                    if (m == l) pos.x = b.x - r;
                    else if (m == ri) pos.x = b.x + b.width + r;
                    else if (m == t) pos.z = b.y - r;
                    else pos.z = b.y + b.height + r;
                } else {
                    float d = (float) Math.sqrt(d2);
                    pos.x = cx + dx / d * r;
                    pos.z = cz + dz / d * r;
                }
            }
        }
        return hit;
    }

    private void updateNpcs(float dt) {
        for (Npc n : npcs) {
            n.bobT += dt;
            n.pos.y = heightAt(n.pos.x, n.pos.z); // jalan mengikuti tanah
            if (n.alongX) {
                n.pos.x += n.dir * n.speed * dt;
                // di persimpangan: kadang belok
                for (float r : ROADS) {
                    if (Math.abs(n.pos.z - r) < 3f && Math.abs(n.pos.x - r) < 3f
                            && rng.nextFloat() < dt * 0.35f) {
                        n.alongX = false;
                        n.dir = rng.nextBoolean() ? 1f : -1f;
                        n.heading = n.dir > 0 ? 0f : MathUtils.PI;
                        n.pos.z = r + (rng.nextBoolean() ? 7.5f : -7.5f);
                        break;
                    }
                }
                if (n.pos.x > HALF - 4f) { n.pos.x = HALF - 4f; n.dir = -1f; }
                if (n.pos.x < -HALF + 4f) { n.pos.x = -HALF + 4f; n.dir = 1f; }
                n.heading = n.dir > 0 ? MathUtils.PI / 2f : -MathUtils.PI / 2f;
            } else {
                n.pos.z += n.dir * n.speed * dt;
                for (float r : ROADS) {
                    if (Math.abs(n.pos.x - r) < 3f && Math.abs(n.pos.z - r) < 3f
                            && rng.nextFloat() < dt * 0.35f) {
                        n.alongX = true;
                        n.dir = rng.nextBoolean() ? 1f : -1f;
                        n.heading = n.dir > 0 ? MathUtils.PI / 2f : -MathUtils.PI / 2f;
                        n.pos.x = r + (rng.nextBoolean() ? 7.5f : -7.5f);
                        break;
                    }
                }
                if (n.pos.z > HALF - 4f) { n.pos.z = HALF - 4f; n.dir = -1f; }
                if (n.pos.z < -HALF + 4f) { n.pos.z = -HALF + 4f; n.dir = 1f; }
                n.heading = n.dir > 0 ? 0f : MathUtils.PI;
            }
        }
    }

    private void updateTraffic(float dt) {
        for (TrafficCar t : traffic) {
            float dx = t.tx - t.pos.x, dz = t.tz - t.pos.z;
            if (dx * dx + dz * dz < 5f * 5f) pickNextTarget(t);
            float want = carAhead(t) ? 0f : t.targetSpeed;
            t.speed += (want - t.speed) * Math.min(1f, 2.5f * dt);
            if (t.speed < 0.05f && want == 0f) t.speed = 0f;
            // belok halus ke arah target (waypoint persimpangan)
            float desired = (float) Math.atan2(t.tx - t.pos.x, t.tz - t.pos.z);
            t.heading = lerpAngle(t.heading, desired, 1f - (float) Math.exp(-3.0f * dt));
            t.pos.x += MathUtils.sin(t.heading) * t.speed * dt;
            t.pos.z += MathUtils.cos(t.heading) * t.speed * dt;
            t.pos.y = heightAt(t.pos.x, t.pos.z); // 0 di jalan
        }
    }

    /** True bila ada kendaraan/orang tepat di depan mobil traffic (jarak < 8). */
    private boolean carAhead(TrafficCar t) {
        float fx = MathUtils.sin(t.heading), fz = MathUtils.cos(t.heading);
        for (TrafficCar o : traffic) {
            if (o != t && ahead(t, o.pos, fx, fz)) return true;
        }
        for (Car c : cars) {
            if (ahead(t, c.pos, fx, fz)) return true;
        }
        if (driving && currentCar != null) {
            if (ahead(t, currentCar.pos, fx, fz)) return true;
        } else if (ahead(t, pPos, fx, fz)) {
            return true;
        }
        return false;
    }

    private boolean ahead(TrafficCar t, Vector3 p, float fx, float fz) {
        float dx = p.x - t.pos.x, dz = p.z - t.pos.z;
        float d2 = dx * dx + dz * dz;
        if (d2 > 64f) return false; // > 8 unit
        float d = (float) Math.sqrt(d2);
        if (d < 0.01f) return true;
        return (dx * fx + dz * fz) / d > 0.7f;
    }

    private void updateCoins(float dt) {
        if (inInterior) return; // koin kota tidak bisa diambil dari bawah tanah
        Vector3 ref = driving && currentCar != null ? currentCar.pos : pPos;
        float collectR = driving ? 2.6f : 1.8f;
        for (Coin c : coins) {
            if (c.taken) continue;
            float dx = ref.x - c.pos.x, dz = ref.z - c.pos.z;
            if (dx * dx + dz * dz < collectR * collectR) {
                c.taken = true;
                coinCount++;
                if (soundOn && coinSnd != null) coinSnd.play(0.9f);
                if (coinCount >= coins.size && !won) {
                    won = true;
                    if (soundOn && jingleSnd != null) jingleSnd.play(0.9f);
                }
                updateHudText();
            }
        }
    }

    private void updateCamera(float dt) {
        Vector3 target = driving && currentCar != null ? currentCar.pos : pPos;
        float heading = driving && currentCar != null ? currentCar.heading : pHeading;
        // lookAt: kepala target + sedikit ke depan arah geraknya.
        camTarget.set(target.x + MathUtils.sin(heading) * 0.8f,
                target.y + 1.4f,
                target.z + MathUtils.cos(heading) * 0.8f);
        // Auto-center HANYA saat nyetir kencang dan user tidak baru saja drag
        // (ala GTA: kamera kembali ke belakang mobil saat ngebut, pelan 1.5/dtk).
        // Saat jalan kaki kamera TIDAK PERNAH dirampas — di v1.0 inilah yang
        // bikin kamera terasa "rusak/aneh" (berayun 180° saat jalan mundur).
        if (driving && currentCar != null && Math.abs(currentCar.speed) > 6f && dragPause <= 0f) {
            camYaw = lerpAngle(camYaw, heading + MathUtils.PI, 1f - (float) Math.exp(-1.5f * dt));
        }
        // Smoothing: semua nilai render menuju target (~9/dtk; posisi target 12/dtk
        // supaya tidak ketinggalan saat lari).
        float k = 1f - (float) Math.exp(-9f * dt);
        float kPos = 1f - (float) Math.exp(-12f * dt);
        camTargetS.lerp(camTarget, kPos);
        camYawS = lerpAngle(camYawS, camYaw, k);
        camPitchS += (camPitch - camPitchS) * k;
        camDistS += (camDist - camDistS) * k;
        float cp = MathUtils.cos(camPitchS), sp = MathUtils.sin(camPitchS);
        cam.position.set(
                camTargetS.x + MathUtils.sin(camYawS) * cp * camDistS,
                camTargetS.y + sp * camDistS,
                camTargetS.z + MathUtils.cos(camYawS) * cp * camDistS);
        // Tinggi minimum kamera terhadap tanah setempat (bukit) / lantai interior.
        if (!inInterior) {
            float gy = heightAt(cam.position.x, cam.position.z) + 0.6f;
            if (cam.position.y < gy) cam.position.y = gy;
        } else {
            // Di dalam ruangan: kamera di antara lantai (-60) dan langit-langit (-56).
            if (cam.position.y < -59.4f) cam.position.y = -59.4f;
            if (cam.position.y > -56.3f) cam.position.y = -56.3f;
        }
        // JAMINAN ANTI-ROLL (v1.0.2): up vector DI-RESET ke (0,1,0) SETIAP frame
        // sebelum lookAt. Posisi kamera dihitung murni dari koordinat bola
        // (yaw/pitch/jarak) dan orientasi HANYA dari lookAt — objek kamera
        // TIDAK PERNAH dirotasi (tanpa camera.rotate/rotateAround di mana pun).
        // Dengan up tegak, Matrix4.setToLookAt membangun sumbu-X horizontal
        // sehingga horizon SELALU datar — roll tidak mungkin terjadi.
        cam.up.set(0f, 1f, 0f);
        cam.lookAt(camTargetS);
        cam.update();
    }

    private void updateHum() {
        if (humSnd == null) return;
        if (!soundOn) {
            if (humId >= 0) { humSnd.stop(humId); humId = -1; }
            return;
        }
        if (driving && currentCar != null && Math.abs(currentCar.speed) > 0.3f) {
            float s = Math.abs(currentCar.speed) / 28f;
            if (humId < 0) humId = humSnd.loop(0.25f);
            humSnd.setVolume(humId, 0.12f + 0.2f * s);
            humSnd.setPitch(humId, 0.6f + 1.4f * s);
        } else if (humId >= 0) {
            humSnd.stop(humId);
            humId = -1;
        }
    }

    private float lerpAngle(float a, float b, float t) {
        float d = (b - a) % MathUtils.PI2;
        if (d > MathUtils.PI) d -= MathUtils.PI2;
        if (d < -MathUtils.PI) d += MathUtils.PI2;
        return a + d * t;
    }

    private void updateHudText() {
        hudCoins = "KOIN: " + coinCount + "/" + coins.size;
        hudFps = "FPS: " + fpsValue;
    }

    private Car nearestCar() {
        Car best = null;
        float bestD = 3.5f * 3.5f;
        for (Car c : cars) {
            float dx = c.pos.x - pPos.x, dz = c.pos.z - pPos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD) {
                bestD = d2;
                best = c;
            }
        }
        return best;
    }

    private void tapSound() {
        if (soundOn && tapSnd != null) tapSnd.play(0.7f);
    }

    // ================= RENDER =================

    private boolean visible(Vector3 center, float radius) {
        return cam.frustum.sphereInFrustum(center, radius);
    }

    private void renderScene() {
        Gdx.gl.glClearColor(0.55f, 0.75f, 0.93f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        if (inInterior) {
            // Di dalam gedung: hanya ruangan + pemain (kota di atas tak terlihat).
            modelBatch.begin(cam);
            modelBatch.render(rooms.get(curRoom).inst, env);
            renderPlayer();
            modelBatch.end();
            renderHud();
            return;
        }
        modelBatch.begin(cam);
        modelBatch.render(groundInst, env);
        modelBatch.render(hazeInst, env);
        modelBatch.render(buildingsInst, env);
        modelBatch.render(treesInst, env);
        modelBatch.render(lampsInst, env);
        if (!driving) renderPlayer();
        for (Car c : cars) if (visible(c.pos, 3f)) renderCar(c);
        for (TrafficCar t : traffic) if (visible(t.pos, 3f)) renderTraffic(t);
        for (Npc n : npcs) if (visible(n.pos, 1.5f)) renderNpc(n);
        for (Coin c : coins) if (!c.taken && visible(c.pos, 1f)) renderCoin(c);
        modelBatch.end();
        renderHud();
        if (mapOpen) renderFullMap();
    }

    private void renderTraffic(TrafficCar t) {
        float hd = t.heading * MathUtils.radiansToDegrees;
        t.inst.transform.setToTranslation(t.pos).rotate(Vector3.Y, hd);
        modelBatch.render(t.inst, env);
    }

    private void renderPlayer() {
        float hd = pHeading * MathUtils.radiansToDegrees;
        float swing = pOnGround ? MathUtils.sin(walkPhase) * 0.55f : 0f;
        float swD = swing * MathUtils.radiansToDegrees;
        pTorso.transform.setToTranslation(pPos).rotate(Vector3.Y, hd);
        pHead.transform.setToTranslation(pPos).rotate(Vector3.Y, hd);
        tmp.set(0.51f, 1.42f, 0).rotate(Vector3.Y, hd).add(pPos);
        pArmL.transform.setToTranslation(tmp).rotate(Vector3.Y, hd).rotate(Vector3.X, swD);
        tmp.set(-0.51f, 1.42f, 0).rotate(Vector3.Y, hd).add(pPos);
        pArmR.transform.setToTranslation(tmp).rotate(Vector3.Y, hd).rotate(Vector3.X, -swD);
        tmp.set(0.2f, 0.9f, 0).rotate(Vector3.Y, hd).add(pPos);
        pLegL.transform.setToTranslation(tmp).rotate(Vector3.Y, hd).rotate(Vector3.X, -swD);
        tmp.set(-0.2f, 0.9f, 0).rotate(Vector3.Y, hd).add(pPos);
        pLegR.transform.setToTranslation(tmp).rotate(Vector3.Y, hd).rotate(Vector3.X, swD);
        pBlob.transform.setToTranslation(pPos.x, pPos.y + 0.03f, pPos.z);
        modelBatch.render(pTorso, env);
        modelBatch.render(pHead, env);
        modelBatch.render(pArmL, env);
        modelBatch.render(pArmR, env);
        modelBatch.render(pLegL, env);
        modelBatch.render(pLegR, env);
        modelBatch.render(pBlob, env);
    }

    private void renderCar(Car c) {
        float hd = c.heading * MathUtils.radiansToDegrees;
        c.body.transform.setToTranslation(c.pos).rotate(Vector3.Y, hd);
        c.cabin.transform.setToTranslation(c.pos).rotate(Vector3.Y, hd);
        float[][] off = {{0.85f, 1.15f}, {-0.85f, 1.15f}, {0.85f, -1.15f}, {-0.85f, -1.15f}};
        for (int i = 0; i < 4; i++) {
            tmp.set(off[i][0], 0.42f, off[i][1]).rotate(Vector3.Y, hd).add(c.pos);
            float steer = (i < 2) ? c.steer * MathUtils.radiansToDegrees : 0f;
            c.wheels[i].transform.setToTranslation(tmp)
                    .rotate(Vector3.Y, hd).rotate(Vector3.Y, steer)
                    .rotate(Vector3.X, c.spin * MathUtils.radiansToDegrees)
                    .rotate(Vector3.Z, 90f);
            modelBatch.render(c.wheels[i], env);
        }
        c.blob.transform.setToTranslation(c.pos.x, c.pos.y + 0.03f, c.pos.z);
        modelBatch.render(c.body, env);
        modelBatch.render(c.cabin, env);
        modelBatch.render(c.blob, env);
    }

    private void renderNpc(Npc n) {
        float hd = n.heading * MathUtils.radiansToDegrees;
        float bob = Math.abs(MathUtils.sin(n.bobT * 6f)) * 0.08f;
        tmp.set(n.pos.x, n.pos.y + bob, n.pos.z);
        n.body.transform.setToTranslation(tmp).rotate(Vector3.Y, hd);
        n.head.transform.setToTranslation(tmp).rotate(Vector3.Y, hd);
        n.blob.transform.setToTranslation(n.pos.x, n.pos.y + 0.03f, n.pos.z);
        modelBatch.render(n.body, env);
        modelBatch.render(n.head, env);
        modelBatch.render(n.blob, env);
    }

    private void renderCoin(Coin c) {
        float bob = MathUtils.sin(time * 2f + c.phase) * 0.15f;
        float spin = (time * 2.5f + c.phase) * MathUtils.radiansToDegrees;
        c.inst.transform.setToTranslation(c.pos.x, c.pos.y + 0.9f + bob, c.pos.z)
                .rotate(Vector3.Y, spin).rotate(Vector3.X, 90f);
        modelBatch.render(c.inst, env);
    }

    // ================= HUD + MINIMAP =================

    private void renderHud() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        ortho.setToOrtho2D(0, 0, w, h);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        shapes.setProjectionMatrix(ortho);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // joystick
        shapes.setColor(0, 0, 0, 0.35f);
        shapes.circle(joyBaseX, h - joyBaseY, 85, 32);
        shapes.setColor(1, 1, 1, 0.5f);
        shapes.circle(joyBaseX + joyX * 55, h - joyBaseY + joyY * 55, 38, 24);
        // tombol
        drawBtn(rSound, "SUARA: " + (soundOn ? "ON" : "OFF"));
        drawBtn(rReset, "RESET");
        if (driving) {
            drawBtn(rGas, "GAS");
            drawBtn(rRem, "REM");
            drawBtn(rEnter, "KELUAR");
        } else {
            drawBtn(rJump, "LOMPAT");
            String lbl = enterLabel();
            if (lbl != null) drawBtn(rEnter, lbl);
        }
        // minimap (pojok kanan atas) — disembunyikan saat di dalam gedung
        if (!inInterior) {
            float mapSize = 190, mapX = w - 210, mapY = h - 210, s = mapSize / WORLD;
            shapes.setColor(0, 0, 0, 0.55f);
            shapes.rect(mapX - 5, mapY - 5, mapSize + 10, mapSize + 10);
            shapes.setColor(0.25f, 0.55f, 0.25f, 1f);
            mapRectOn(mapX, mapY, s, 20, 20, 30, 30); // taman
            shapes.setColor(0.5f, 0.42f, 0.3f, 1f);
            mapRectOn(mapX, mapY, s, -180, -180, 30, 30); // bukit
            mapRectOn(mapX, mapY, s, 140, -140, 30, 30); // bukit
            shapes.setColor(0.25f, 0.25f, 0.28f, 1f);
            for (float c : ROADS) {
                mapRectOn(mapX, mapY, s, 0, c, WORLD, ROAD_W);
                mapRectOn(mapX, mapY, s, c, 0, ROAD_W, WORLD);
            }
            shapes.setColor(1f, 0.8f, 0.2f, 1f);
            for (Coin c : coins) {
                if (c.taken) continue;
                mapDot(mapX, mapY, s, c.pos.x, c.pos.z, 3.5f);
            }
            shapes.setColor(0.3f, 0.6f, 1f, 1f);
            for (Car c : cars) {
                mapDot(mapX, mapY, s, c.pos.x, c.pos.z, 4.5f);
            }
            shapes.setColor(1f, 0.55f, 0.1f, 1f);
            for (TrafficCar t : traffic) {
                mapDot(mapX, mapY, s, t.pos.x, t.pos.z, 4f);
            }
            shapes.setColor(0.3f, 0.9f, 0.4f, 1f);
            for (Enterable e : enterables) {
                mapDot(mapX, mapY, s, e.bx, e.bz, 5f);
            }
            Vector3 ref = driving && currentCar != null ? currentCar.pos : pPos;
            shapes.setColor(1f, 1f, 1f, 1f);
            mapDot(mapX, mapY, s, ref.x, ref.z, 5.5f);
        }
        shapes.end();

        // teks
        batch.setProjectionMatrix(ortho);
        batch.begin();
        font.getData().setScale(2f);
        font.setColor(Color.WHITE);
        font.draw(batch, hudCoins, w / 2f - 100, h - 30);
        font.draw(batch, hudFps, 16, h - 100);
        if (driving) font.draw(batch, hudSpeed, w / 2f - 160, 70);
        if (inInterior) font.draw(batch, "DI DALAM: " + rooms.get(curRoom).name, w / 2f - 110, h - 65);
        if (won) {
            font.getData().setScale(3f);
            font.setColor(Color.GOLD);
            font.draw(batch, "KOTA SELESAI!", w / 2f - 190, h / 2f + 40);
            font.getData().setScale(2f);
            font.setColor(Color.WHITE);
            font.draw(batch, "Semua koin terkumpul!", w / 2f - 170, h / 2f - 10);
        }
        // label tombol
        drawBtnLabel(rSound, "SUARA: " + (soundOn ? "ON" : "OFF"));
        drawBtnLabel(rReset, "RESET");
        if (driving) {
            drawBtnLabel(rGas, "GAS");
            drawBtnLabel(rRem, "REM");
            drawBtnLabel(rEnter, "KELUAR");
        } else {
            drawBtnLabel(rJump, "LOMPAT");
            String lbl = enterLabel();
            if (lbl != null) drawBtnLabel(rEnter, lbl);
        }
        batch.end();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ================= PETA FULLSCREEN + GEDUNG MASUK =================

    /** Kotak (wx,wz,ww,wd) dunia -> rect di peta (px,py = sudut kiri-bawah, y-up). */
    private void mapRectOn(float px, float py, float s, float wx, float wz, float ww, float wd) {
        shapes.rect(px + (wx - ww / 2f + HALF) * s, py + (HALF - wz - wd / 2f) * s, ww * s, wd * s);
    }

    /** Titik (wx,wz) dunia -> lingkaran di peta. */
    private void mapDot(float px, float py, float s, float wx, float wz, float r) {
        shapes.circle(px + (wx + HALF) * s, py + (HALF - wz) * s, r, 10);
    }

    /** Overlay peta fullscreen (dipanggil setelah renderHud, di atas segalanya). */
    private void renderFullMap() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float ps = Math.min(w, h) * 0.8f;
        float px = (w - ps) / 2f, py = (h - ps) / 2f; // y-up
        float s = ps / WORLD;
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(ortho);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 0.6f);
        shapes.rect(0, 0, w, h); // gelapkan dunia di belakang
        shapes.setColor(0.08f, 0.1f, 0.12f, 0.95f);
        shapes.rect(px - 8, py - 8, ps + 16, ps + 16);
        shapes.setColor(0.25f, 0.55f, 0.25f, 1f);
        mapRectOn(px, py, s, 20, 20, 30, 30); // taman
        shapes.setColor(0.5f, 0.42f, 0.3f, 1f);
        mapRectOn(px, py, s, -180, -180, 30, 30); // bukit
        mapRectOn(px, py, s, 140, -140, 30, 30); // bukit
        shapes.setColor(0.3f, 0.3f, 0.34f, 1f);
        for (float c : ROADS) {
            mapRectOn(px, py, s, 0, c, WORLD, ROAD_W);
            mapRectOn(px, py, s, c, 0, ROAD_W, WORLD);
        }
        shapes.setColor(1f, 0.8f, 0.2f, 1f);
        for (Coin c : coins) {
            if (c.taken) continue;
            mapDot(px, py, s, c.pos.x, c.pos.z, 5f);
        }
        shapes.setColor(0.3f, 0.6f, 1f, 1f);
        for (Car c : cars) mapDot(px, py, s, c.pos.x, c.pos.z, 6f);
        shapes.setColor(1f, 0.55f, 0.1f, 1f);
        for (TrafficCar t : traffic) mapDot(px, py, s, t.pos.x, t.pos.z, 6f);
        shapes.setColor(0.3f, 0.9f, 0.4f, 1f);
        for (Enterable e : enterables) mapDot(px, py, s, e.bx, e.bz, 7f);
        Vector3 ref = driving && currentCar != null ? currentCar.pos : pPos;
        shapes.setColor(1f, 1f, 1f, 1f);
        mapDot(px, py, s, ref.x, ref.z, 8f);
        drawBtn(rMapClose, "TUTUP");
        shapes.end();

        batch.setProjectionMatrix(ortho);
        batch.begin();
        font.getData().setScale(2f);
        font.setColor(Color.WHITE);
        font.draw(batch, "PETA KOTA", px, py + ps + 30);
        font.getData().setScale(1.5f);
        font.draw(batch, "Putih=kamu  Biru=mobil parkir  Oranye=traffic", px + 10, py + ps - 12);
        font.draw(batch, "Emas=koin  Hijau=gedung masuk", px + 10, py + ps - 36);
        for (Enterable e : enterables) {
            font.draw(batch, e.name, px + (e.bx + HALF) * s + 10, py + (HALF - e.bz) * s + 5);
        }
        drawBtnLabel(rMapClose, "TUTUP");
        batch.end();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void setMapOpen(boolean open) {
        mapOpen = open;
        if (open && humId >= 0 && humSnd != null) { // pause: matikan hum mesin
            humSnd.stop(humId);
            humId = -1;
        }
        tapSound();
    }

    /** Label tombol MASUK/KELUAR, atau null bila tombol disembunyikan. */
    private String enterLabel() {
        if (driving) return null;
        if (inInterior) return nearExitDoor() ? "KELUAR" : null;
        if (nearestCar() != null) return "MASUK MOBIL";
        Enterable nd = nearestDoor();
        return nd != null ? "MASUK " + nd.name : null;
    }

    private Enterable nearestDoor() {
        Enterable best = null;
        float bestD = 2.5f * 2.5f;
        for (Enterable e : enterables) {
            float dx = e.door.x - pPos.x, dz = e.door.z - pPos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD) {
                bestD = d2;
                best = e;
            }
        }
        return best;
    }

    private boolean nearExitDoor() {
        if (!inInterior) return false;
        Room rm = rooms.get(curRoom);
        float dx = rm.exitDoor.x - pPos.x, dz = rm.exitDoor.z - pPos.z;
        return dx * dx + dz * dz < 2.5f * 2.5f;
    }

    private void enterInterior(Enterable e) {
        curRoom = e.type;
        curEnterable = e;
        inInterior = true;
        Room rm = rooms.get(curRoom);
        pPos.set(rm.center.x, -60f, 0f);
        pHeading = MathUtils.PI; // menghadap pintu keluar (dinding utara)
        pVelY = 0f;
        pOnGround = true;
        camTargetS.set(pPos.x, pPos.y + 1.4f, pPos.z); // tanpa sapuan kamera
        camDist = 4.5f; // ruangan kecil: kamera lebih dekat
        tapSound();
    }

    private void exitInterior() {
        inInterior = false;
        pPos.set(curEnterable.door.x, 0f, curEnterable.door.z);
        pHeading = 0f; // menghadap jalan
        pVelY = 0f;
        pOnGround = true;
        camTargetS.set(pPos.x, pPos.y + 1.4f, pPos.z);
        camDist = 8f;
        tapSound();
    }

    /** Kotak tombol (rect y-down -> y-up). */
    private void drawBtn(Rectangle r, String label) {
        int h = Gdx.graphics.getHeight();
        shapes.setColor(0, 0, 0, 0.45f);
        shapes.rect(r.x, h - r.y - r.height, r.width, r.height);
        shapes.setColor(1, 1, 1, 0.25f);
        shapes.rect(r.x, h - r.y - r.height, r.width, 3);
    }

    private void drawBtnLabel(Rectangle r, String label) {
        int h = Gdx.graphics.getHeight();
        font.draw(batch, label, r.x + 16, h - r.y - r.height / 2f + 14);
    }

    // ================= INPUT =================

    private void updateJoy(float x, float y) {
        float dx = (x - joyBaseX) / 85f;
        float dy = (joyBaseY - y) / 85f;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
        }
        joyX = dx;
        joyY = dy;
    }

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        // Tap minimap (pojok kanan atas; y-down: x [w-210, w-20], y [20, 210])
        // = buka/tutup peta fullscreen. Tidak aktif saat di dalam gedung.
        if (!inInterior && x >= Gdx.graphics.getWidth() - 210 && x <= Gdx.graphics.getWidth() - 20
                && y >= 20 && y <= 210) {
            setMapOpen(!mapOpen);
            return true;
        }
        if (mapOpen) {
            if (rMapClose.contains(x, y)) setMapOpen(false);
            return true; // tap lain saat peta terbuka: abaikan
        }
        if (rSound.contains(x, y)) {
            toggleSound();
            return true;
        }
        if (rReset.contains(x, y)) {
            resetCoins();
            return true;
        }
        if (driving) {
            if (rGas.contains(x, y)) {
                btnGas = true;
                gasId = pointer;
                return true;
            }
            if (rRem.contains(x, y)) {
                btnRem = true;
                remId = pointer;
                return true;
            }
            if (rEnter.contains(x, y)) {
                exitCar();
                return true;
            }
        } else {
            if (rJump.contains(x, y)) {
                jumpQueued = true;
                tapSound();
                return true;
            }
            if (rEnter.contains(x, y)) {
                if (inInterior) {
                    if (nearExitDoor()) exitInterior();
                } else if (nearestCar() != null) {
                    enterCar();
                } else {
                    Enterable nd = nearestDoor();
                    if (nd != null) enterInterior(nd);
                }
                return true;
            }
        }
        // Joystick hanya untuk sentuhan PERTAMA (tidak ada sentuhan lain aktif) —
        // v1.0: jari kedua yang mendarat di kiri merebut joystick sehingga pinch
        // tidak pernah terdaftar.
        if (x < Gdx.graphics.getWidth() * 0.42f && joyId == -1 && camId == -1 && pinchId == -1) {
            joyId = pointer;
            updateJoy(x, y);
            return true;
        }
        if (camId == -1) {
            camId = pointer;
            camLastX = x;
            camLastY = y;
            return true;
        }
        if (pinchId == -1) {
            pinchId = pointer;
            pinchLastX = x;
            pinchLastY = y;
            pinchLastDist = (float) Math.sqrt((x - camLastX) * (x - camLastX)
                    + (y - camLastY) * (y - camLastY));
            return true;
        }
        return true;
    }

    @Override
    public boolean touchDragged(int x, int y, int pointer) {
        if (mapOpen) return true; // peta terbuka: drag dunia dinonaktifkan
        if (pointer == joyId) {
            updateJoy(x, y);
            return true;
        }
        if (pointer == pinchId && camId != -1) {
            pinchLastX = x;
            pinchLastY = y;
            float d = (float) Math.sqrt((x - camLastX) * (x - camLastX)
                    + (y - camLastY) * (y - camLastY));
            if (pinchLastDist > 1f && d > 1f) {
                // Target jarak (bukan langsung) — zoom ikut dihaluskan di updateCamera.
                camDist = MathUtils.clamp(camDist * pinchLastDist / d, 4f, 14f);
            }
            pinchLastDist = d;
            return true;
        }
        if (pointer == camId && pinchId == -1) {
            camYaw -= (x - camLastX) * 0.005f; // geser kanan = pandangan belok kanan
            // Konvensi orbit (ala Genshin/GTA mobile): geser ke atas = kamera naik
            // (lihat ke bawah); geser ke bawah = kamera turun (lihat ke atas).
            // v1.0 memakai arah sebaliknya (konvensi FPS) — terasa aneh di kamera orbit.
            camPitch = MathUtils.clamp(camPitch - (y - camLastY) * 0.004f, -0.087f, 0.96f);
            camLastX = x;
            camLastY = y;
            dragPause = 2.5f;
            return true;
        }
        if (pointer == camId && pinchId != -1) {
            camLastX = x;
            camLastY = y;
            return true;
        }
        return true;
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int button) {
        if (pointer == joyId) {
            joyId = -1;
            joyX = 0;
            joyY = 0;
        } else if (pointer == camId) {
            camId = -1;
            pinchId = -1;
        } else if (pointer == pinchId) {
            pinchId = -1;
        }
        if (pointer == gasId) {
            btnGas = false;
            gasId = -1;
        }
        if (pointer == remId) {
            btnRem = false;
            remId = -1;
        }
        return true;
    }

    private void enterCar() {
        Car c = nearestCar();
        if (c == null) return;
        currentCar = c;
        driving = true;
        c.speed = 0;
        // v1.0 men-snap camYaw ke belakang mobil di sini (jump cut). v1.0.1: kamera
        // dibiarkan di tempatnya; auto-center lembut di updateCamera yang mengurus
        // saat mobil sudah ngebut.
        tapSound();
    }

    private void exitCar() {
        Car c = currentCar;
        if (c == null) return;
        float rx = MathUtils.cos(c.heading), rz = -MathUtils.sin(c.heading);
        pPos.set(c.pos.x + rx * 2.4f, 0, c.pos.z + rz * 2.4f);
        pHeading = c.heading;
        driving = false;
        currentCar = null;
        btnGas = false;
        btnRem = false;
        tapSound();
    }

    private void toggleSound() {
        soundOn = !soundOn;
        prefs.putBoolean("soundOn", soundOn);
        prefs.flush();
        if (!soundOn && humId >= 0 && humSnd != null) {
            humSnd.stop(humId);
            humId = -1;
        }
        tapSound();
    }

    private void resetCoins() {
        for (Coin c : coins) c.taken = false;
        coinCount = 0;
        won = false;
        updateHudText();
        tapSound();
    }

    @Override
    public boolean keyDown(int keycode) {
        if ((keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) && mapOpen) {
            setMapOpen(false);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyUp(int keycode) {
        return false;
    }

    @Override
    public boolean keyTyped(char character) {
        return false;
    }

    @Override
    public boolean touchCancelled(int x, int y, int pointer, int button) {
        return touchUp(x, y, pointer, button);
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return false;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        return false;
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
        layoutButtons();
    }

    @Override
    public void dispose() {
        for (Model m : models) m.dispose();
        for (Texture t : textures) t.dispose();
        modelBatch.dispose();
        batch.dispose();
        shapes.dispose();
        font.dispose();
        if (humSnd != null) humSnd.dispose();
        if (tapSnd != null) tapSnd.dispose();
        if (coinSnd != null) coinSnd.dispose();
        if (jumpSnd != null) jumpSnd.dispose();
        if (jingleSnd != null) jingleSnd.dispose();
    }
}
