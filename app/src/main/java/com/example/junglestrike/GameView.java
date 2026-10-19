package com.example.junglestrike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/** Original run-and-gun side scroller. Everything is drawn with Canvas primitives. */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    static final float VH = 360f;          // virtual screen height
    static final float G = 0.55f;          // gravity per frame
    static final float LEVEL_LEN = 7200f;
    static final float NONE = Float.MAX_VALUE;
    static final int TITLE = 0, PLAY = 1, OVER = 2, WIN = 3;

    static class Plat {
        float x, y, w; boolean ground;
        Plat(float x, float y, float w, boolean g) { this.x = x; this.y = y; this.w = w; this.ground = g; }
    }

    static class Bullet {
        float x, y, vx, vy; boolean friendly; int life;
    }

    static class Enemy {
        int type; float x, y, vx, vy, w, h; int hp, timer, flash; boolean active;
    }

    static class Power {
        float x, y; int kind; boolean taken;
        Power(float x, float y, int k) { this.x = x; this.y = y; this.kind = k; }
    }

    static class Part {
        float x, y, vx, vy; int life, color;
    }

    final SurfaceHolder holder;
    Thread thread;
    volatile boolean running;
    volatile boolean startRequested;
    volatile boolean bLeft, bRight, bUp, bJump, bFire;

    int W = 1280, H = 720;
    float scale = 2f, vw = 640f;
    int state = TITLE;
    long frame, stateSince;
    final Random rnd = new Random(3);

    final ArrayList<Plat> plats = new ArrayList<>();
    final ArrayList<Enemy> enemies = new ArrayList<>();
    final ArrayList<Bullet> bullets = new ArrayList<>();
    final ArrayList<Power> powers = new ArrayList<>();
    final ArrayList<Part> parts = new ArrayList<>();

    float camX;
    float px, py, pvx, pvy, aimX = 1, aimY = 0;
    boolean onGround, jumpLatch, bossDead;
    int facing = 1, invuln, cooldown, lives = 3, weapon, score, winTimer;

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF rf = new RectF();
    Paint skyPaint;
    final float[] starX = new float[60];
    final float[] starY = new float[60];

    // button layout (screen px)
    float bs, cLx, cLy, cRx, cRy, cUx, cUy, cJx, cJy, cFx, cFy;

    public GameView(Context ctx) {
        super(ctx);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        Random r = new Random(5);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = r.nextFloat();
            starY[i] = r.nextFloat() * 170f;
        }
        buildLevel();
    }

    // ------------------------------------------------------------ lifecycle

    @Override public void surfaceCreated(SurfaceHolder h) {
        running = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        W = w; H = hh;
        scale = H / VH;
        vw = W / scale;
        skyPaint = new Paint();
        skyPaint.setShader(new LinearGradient(0, 0, 0, H, 0xFF070B1E, 0xFF1D3B4A, Shader.TileMode.CLAMP));
        bs = H * 0.2f;
        cLx = bs * 0.9f;  cLy = H - bs * 0.75f;
        cRx = bs * 2.45f; cRy = cLy;
        cUx = bs * 1.675f; cUy = H - bs * 1.85f;
        cFx = W - bs * 0.9f;  cFy = H - bs * 0.8f;
        cJx = W - bs * 2.45f; cJy = cFy;
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try { if (thread != null) thread.join(); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
        long last = System.nanoTime();
        double acc = 0, step = 1e9 / 60.0;
        while (running) {
            long now = System.nanoTime();
            acc += now - last;
            last = now;
            if (acc > step * 5) acc = step * 5;
            while (acc >= step) { update(); acc -= step; }
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) drawGame(c);
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
            try { Thread.sleep(4); } catch (InterruptedException ignored) { }
        }
    }

    // ------------------------------------------------------------ input

    boolean in(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy, r = bs * 0.72f;
        return dx * dx + dy * dy < r * r;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (state != PLAY) {
            bLeft = bRight = bUp = bJump = bFire = false;
            if (a == MotionEvent.ACTION_DOWN) startRequested = true;
            return true;
        }
        boolean l = false, r = false, u = false, j = false, f = false;
        int skip = (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) ? ev.getActionIndex() : -1;
        if (a != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < ev.getPointerCount(); i++) {
                if (i == skip) continue;
                float x = ev.getX(i), y = ev.getY(i);
                if (in(x, y, cLx, cLy)) l = true;
                if (in(x, y, cRx, cRy)) r = true;
                if (in(x, y, cUx, cUy)) u = true;
                if (in(x, y, cJx, cJy)) j = true;
                if (in(x, y, cFx, cFy)) f = true;
            }
        }
        bLeft = l; bRight = r; bUp = u; bJump = j; bFire = f;
        return true;
    }

    // ------------------------------------------------------------ level

    Enemy mk(int type, float x, float y) {
        Enemy e = new Enemy();
        e.type = type; e.x = x; e.y = y;
        if (type == 0) { e.w = 18; e.h = 34; e.hp = 1; e.timer = 40 + rnd.nextInt(80); }
        else if (type == 1) { e.w = 26; e.h = 22; e.hp = 4; e.timer = 60 + rnd.nextInt(60); }
        else { e.w = 64; e.h = 96; e.hp = 80; e.timer = 90; }
        return e;
    }

    void buildLevel() {
        plats.clear(); enemies.clear(); powers.clear(); bullets.clear(); parts.clear();
        Random r = new Random(11);
        float x = -100f;
        int seg = 0;
        while (x < LEVEL_LEN - 1000f) {
            float len = 420f + r.nextInt(300);
            plats.add(new Plat(x, 300f, len, true));

            // floating platforms: one at mid height, optionally a higher one chained to it
            float pw = 90f + r.nextInt(60);
            float p1x = x + 40f + r.nextInt((int) (len - pw - 80f));
            plats.add(new Plat(p1x, 230f, pw, false));
            if (seg > 0 || p1x > 400f) {
                if (r.nextInt(3) != 0) enemies.add(mk(1, p1x + pw / 2f, 230f));
            }
            if (r.nextInt(3) == 0) powers.add(new Power(p1x + pw / 2f, 212f, 1 + r.nextInt(3)));
            if (r.nextBoolean()) {
                float pw2 = 80f + r.nextInt(50);
                float p2x = Math.min(p1x + pw - 30f, x + len - pw2 - 10f);
                plats.add(new Plat(p2x, 165f, pw2, false));
                if (r.nextInt(3) == 0) powers.add(new Power(p2x + pw2 / 2f, 147f, 1 + r.nextInt(3)));
            }

            // ground soldiers
            int s = 1 + r.nextInt(3) + seg / 3;
            for (int i = 0; i < s; i++) {
                float minX = (seg == 0) ? 520f : x + 120f;
                float sx = minX + r.nextInt(Math.max(40, (int) (x + len - 40f - minX)));
                if (sx < x + len - 20f) enemies.add(mk(0, sx, 300f));
            }
            x += len + 60f + r.nextInt(25);
            seg++;
        }
        // boss arena: solid ground to the end
        plats.add(new Plat(x, 300f, LEVEL_LEN + 400f - x, true));
        enemies.add(mk(2, LEVEL_LEN - 110f, 250f));
    }

    void resetGame() {
        buildLevel();
        camX = 0; px = 60; py = 150; pvx = pvy = 0;
        lives = 3; weapon = 0; score = 0; invuln = 90; cooldown = 0;
        bossDead = false; winTimer = 0; facing = 1;
    }

    // ------------------------------------------------------------ helpers

    static boolean overlap(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    float landY(float cx, float hw, float prevFeet, float feet) {
        float best = NONE;
        for (int i = 0; i < plats.size(); i++) {
            Plat q = plats.get(i);
            if (cx + hw > q.x && cx - hw < q.x + q.w && prevFeet <= q.y + 0.01f && feet >= q.y && q.y < best)
                best = q.y;
        }
        return best;
    }

    boolean hasSupport(float cx, float feet) {
        for (int i = 0; i < plats.size(); i++) {
            Plat q = plats.get(i);
            if (cx > q.x && cx < q.x + q.w && Math.abs(q.y - feet) < 1.5f) return true;
        }
        return false;
    }

    void addBullet(float x, float y, float ang, float spd, boolean friendly) {
        Bullet b = new Bullet();
        b.x = x; b.y = y;
        b.vx = (float) Math.cos(ang) * spd;
        b.vy = (float) Math.sin(ang) * spd;
        b.friendly = friendly;
        b.life = friendly ? 70 : 400;
        bullets.add(b);
    }

    void boom(float x, float y, int n, int color) {
        for (int i = 0; i < n; i++) {
            Part q = new Part();
            double a = rnd.nextDouble() * Math.PI * 2;
            float s = 0.5f + rnd.nextFloat() * 3f;
            q.x = x; q.y = y;
            q.vx = (float) Math.cos(a) * s;
            q.vy = (float) Math.sin(a) * s - 0.5f;
            q.life = 15 + rnd.nextInt(20);
            q.color = color;
            parts.add(q);
        }
    }

    void setState(int s) { state = s; stateSince = frame; }

    // ------------------------------------------------------------ update

    void update() {
        frame++;
        if (startRequested) {
            startRequested = false;
            if (state != PLAY && frame - stateSince > 40) { resetGame(); setState(PLAY); }
        }
        updateParts();
        if (state != PLAY) return;
        updatePlayer();
        updateEnemies();
        updateBullets();
        updatePowers();

        float target = px - vw * 0.4f;
        if (target > camX) camX = target;
        float maxCam = LEVEL_LEN - vw;
        if (camX > maxCam) camX = maxCam;

        if (bossDead) {
            if (--winTimer <= 0) setState(WIN);
        }
    }

    void updatePlayer() {
        boolean l = bLeft, r = bRight;
        pvx = 0;
        if (l && !r) { pvx = -2.6f; facing = -1; }
        else if (r && !l) { pvx = 2.6f; facing = 1; }

        if (bJump) {
            if (onGround && !jumpLatch) { pvy = -10.5f; onGround = false; }
            jumpLatch = true;
        } else jumpLatch = false;

        px += pvx;
        if (px < camX + 12f) px = camX + 12f;
        if (px > LEVEL_LEN + 200f) px = LEVEL_LEN + 200f;

        float prev = py;
        pvy += G;
        if (pvy > 12f) pvy = 12f;
        py += pvy;
        onGround = false;
        if (pvy >= 0) {
            float ly = landY(px, 8f, prev, py);
            if (ly != NONE) { py = ly; pvy = 0; onGround = true; }
        }

        if (invuln > 0) invuln--;
        if (cooldown > 0) cooldown--;

        boolean moving = l != r;
        float dx = (bUp && !moving) ? 0f : facing;
        float dy = bUp ? -1f : 0f;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        aimX = dx / len; aimY = dy / len;
        if (bFire && cooldown <= 0) shoot();

        if (py > VH + 60f) hurtPlayer(true);
    }

    void shoot() {
        float ang = (float) Math.atan2(aimY, aimX);
        float ox = px + aimX * 12f, oy = py - 22f + aimY * 10f;
        if (weapon == 1) {
            for (int k = -1; k <= 1; k++) addBullet(ox, oy, ang + k * 0.26f, 7.5f, true);
            cooldown = 14;
        } else {
            addBullet(ox, oy, ang, 8f, true);
            cooldown = (weapon == 2) ? 5 : 11;
        }
    }

    void hurtPlayer(boolean force) {
        if (!force && invuln > 0) return;
        boom(px, py - 18f, 26, 0xFFFFAA33);
        lives--;
        weapon = 0;
        if (lives <= 0) { setState(OVER); return; }
        Plat best = null;
        for (int i = 0; i < plats.size(); i++) {
            Plat q = plats.get(i);
            if (q.ground && q.x + q.w > camX + 90f) { best = q; break; }
        }
        px = (best == null) ? camX + 40f : Math.max(best.x + 20f, camX + 40f);
        py = (best == null) ? 100f : best.y - 70f;
        pvx = pvy = 0;
        invuln = 120;
    }

    void damageEnemy(Enemy e) {
        e.hp--;
        e.flash = 4;
        boom(e.x, e.y - e.h / 2f, 3, 0xFFFFE066);
        if (e.hp <= 0) {
            enemies.remove(e);
            boom(e.x, e.y - e.h / 2f, e.type == 2 ? 80 : 16, 0xFFFF7043);
            score += e.type == 0 ? 100 : (e.type == 1 ? 300 : 5000);
            if (e.type == 2) { bossDead = true; winTimer = 120; }
        }
    }

    void updateEnemies() {
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            if (!e.active) {
                if (e.type == 2) { if (camX >= LEVEL_LEN - vw - 2f) e.active = true; }
                else if (e.x < camX + vw + 40f) e.active = true;
                if (!e.active) continue;
            }
            if (e.flash > 0) e.flash--;
            e.timer--;
            boolean visible = e.x > camX && e.x < camX + vw;

            if (e.type == 0) {
                float dir = px > e.x ? 1f : -1f;
                e.vx = 0;
                if (Math.abs(px - e.x) < 420f && e.vy == 0 && hasSupport(e.x + dir * 12f, e.y)) e.vx = dir * 0.9f;
                e.x += e.vx;
                float prev = e.y;
                e.vy += G; e.y += e.vy;
                float ly = landY(e.x, 9f, prev, e.y);
                if (ly != NONE) { e.y = ly; e.vy = 0; }
                if (e.y > VH + 80f || e.x < camX - 80f) { enemies.remove(i); continue; }
                if (e.timer <= 0) {
                    e.timer = 100 + rnd.nextInt(80);
                    if (visible && Math.abs(px - e.x) < 380f) {
                        float ang = (float) Math.atan2(py - 20f - (e.y - 22f), px - e.x);
                        addBullet(e.x, e.y - 22f, ang, 3.2f, false);
                    }
                }
            } else if (e.type == 1) {
                if (e.timer <= 0) {
                    e.timer = 85 + rnd.nextInt(40);
                    if (visible) {
                        float ang = (float) Math.atan2(py - 20f - (e.y - 14f), px - e.x);
                        addBullet(e.x, e.y - 14f, ang, 3.4f, false);
                    }
                }
            } else {
                e.y = 250f + (float) Math.sin(frame * 0.025) * 50f;
                if (e.timer <= 0) {
                    e.timer = 65;
                    float base = (float) Math.atan2(py - 20f - (e.y - 50f), px - (e.x - 30f));
                    int n = (frame / 65) % 2 == 0 ? 5 : 3;
                    for (int k = -(n / 2); k <= n / 2; k++) addBullet(e.x - 30f, e.y - 50f, base + k * 0.24f, 3f, false);
                }
            }

            if (invuln <= 0 && overlap(px - 8, py - 36, 16, 36, e.x - e.w / 2f, e.y - e.h, e.w, e.h)) {
                hurtPlayer(false);
                if (state != PLAY) return;
            }
        }
    }

    void updateBullets() {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.x += b.vx; b.y += b.vy; b.life--;
            boolean dead = b.life <= 0 || b.x < camX - 30f || b.x > camX + vw + 30f || b.y < -30f || b.y > VH + 30f;
            if (!dead) {
                if (b.friendly) {
                    for (int j = enemies.size() - 1; j >= 0; j--) {
                        Enemy e = enemies.get(j);
                        if (!e.active) continue;
                        if (overlap(b.x - 3, b.y - 3, 6, 6, e.x - e.w / 2f, e.y - e.h, e.w, e.h)) {
                            damageEnemy(e);
                            dead = true;
                            break;
                        }
                    }
                } else if (invuln <= 0 && overlap(b.x - 3, b.y - 3, 6, 6, px - 8, py - 36, 16, 36)) {
                    hurtPlayer(false);
                    dead = true;
                    if (state != PLAY) return;
                }
            }
            if (dead) bullets.remove(i);
        }
    }

    void updatePowers() {
        for (int i = 0; i < powers.size(); i++) {
            Power w = powers.get(i);
            if (w.taken) continue;
            if (overlap(px - 8, py - 36, 16, 36, w.x - 9, w.y - 7, 18, 14)) {
                w.taken = true;
                if (w.kind == 3) lives = Math.min(lives + 1, 9);
                else weapon = w.kind;
                score += 50;
                boom(w.x, w.y, 10, 0xFFFFFFFF);
            }
        }
    }

    void updateParts() {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part q = parts.get(i);
            q.x += q.vx; q.y += q.vy; q.vy += 0.12f;
            if (--q.life <= 0) parts.remove(i);
        }
    }

    // ------------------------------------------------------------ drawing

    void drawGame(Canvas c) {
        if (skyPaint != null) c.drawRect(0, 0, W, H, skyPaint); else c.drawColor(0xFF0B1426);
        c.save();
        c.scale(scale, scale);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < starX.length; i++) {
            float sx = (starX[i] * vw - camX * 0.05f) % vw;
            if (sx < 0) sx += vw;
            c.drawRect(sx, starY[i], sx + 1.5f, starY[i] + 1.5f, p);
        }
        p.setColor(0xFFE8EEF5);
        c.drawCircle(vw * 0.8f, 60f, 22f, p);
        drawHills(c, 0.25f, 30f, 120f, 0xFF14243F);
        drawHills(c, 0.5f, 14f, 90f, 0xFF0F2F2A);

        c.save();
        c.translate(-camX, 0);

        for (int i = 0; i < plats.size(); i++) {
            Plat q = plats.get(i);
            if (q.x + q.w < camX || q.x > camX + vw) continue;
            if (q.ground) {
                p.setColor(0xFF3E2A1A); c.drawRect(q.x, q.y, q.x + q.w, VH, p);
                p.setColor(0xFF2E8B3E); c.drawRect(q.x, q.y, q.x + q.w, q.y + 8f, p);
                p.setColor(0xFF1F6B2E); c.drawRect(q.x, q.y + 8f, q.x + q.w, q.y + 11f, p);
            } else {
                p.setColor(0xFF7F8C8D); c.drawRect(q.x, q.y, q.x + q.w, q.y + 10f, p);
                p.setColor(0xFFBDC3C7); c.drawRect(q.x, q.y, q.x + q.w, q.y + 3f, p);
                p.setColor(0xFF566573);
                for (float rx = q.x + 6f; rx < q.x + q.w - 4f; rx += 16f) c.drawRect(rx, q.y + 5f, rx + 3f, q.y + 8f, p);
            }
        }

        for (int i = 0; i < powers.size(); i++) {
            Power w = powers.get(i);
            if (w.taken || w.x < camX - 20f || w.x > camX + vw + 20f) continue;
            float bob = (float) Math.sin(frame * 0.1 + w.x) * 2f;
            int col = w.kind == 1 ? 0xFFE53935 : (w.kind == 2 ? 0xFFFDD835 : 0xFF43A047);
            p.setColor(col);
            rf.set(w.x - 9f, w.y - 7f + bob, w.x + 9f, w.y + 7f + bob);
            c.drawRoundRect(rf, 6f, 6f, p);
            p.setColor(0xFFFFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(w.kind == 1 ? "S" : (w.kind == 2 ? "R" : "L"), w.x, w.y + 4f + bob, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            if (!e.active) continue;
            drawEnemy(c, e);
        }

        if (state == PLAY || state == WIN || state == OVER) { if (state == PLAY) drawPlayer(c); }

        for (int i = 0; i < bullets.size(); i++) {
            Bullet b = bullets.get(i);
            p.setColor(b.friendly ? 0xFFFFF176 : 0xFFFF5252);
            c.drawCircle(b.x, b.y, b.friendly ? 2.6f : 3.4f, p);
        }
        for (int i = 0; i < parts.size(); i++) {
            Part q = parts.get(i);
            p.setColor(q.color);
            c.drawRect(q.x, q.y, q.x + 2.5f, q.y + 2.5f, p);
        }
        c.restore();
        c.restore();

        drawHud(c);
    }

    void drawHills(Canvas c, float par, float step, float maxH, int color) {
        p.setColor(color);
        float off = camX * par;
        int idx = (int) Math.floor(off / step);
        float fo = off - idx * step;
        int n = (int) (vw / step) + 3;
        for (int i = 0; i < n; i++) {
            int wi = idx + i;
            float h = maxH * (0.45f + 0.35f * (float) Math.abs(Math.sin(wi * 0.61f))
                    + 0.2f * (float) Math.abs(Math.sin(wi * 0.17f + 1f)));
            c.drawRect(i * step - fo, 300f - h, i * step - fo + step + 1f, VH, p);
        }
    }

    void drawPlayer(Canvas c) {
        if (invuln > 0 && (invuln / 4) % 2 == 0) return;
        float leg = (onGround && pvx != 0) ? (float) Math.sin(frame * 0.35) * 4f : 0f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2C3E50);
        c.drawRect(px - 6 + leg, py - 14, px - 1 + leg, py, p);
        c.drawRect(px + 1 - leg, py - 14, px + 6 - leg, py, p);
        p.setColor(0xFFE67E22); c.drawRect(px - 8, py - 27, px + 8, py - 13, p);
        p.setColor(0xFFF5CBA7); c.drawCircle(px, py - 32, 6, p);
        p.setColor(0xFFECF0F1); c.drawRect(px - 7, py - 39, px + 7, py - 34, p);
        p.setColor(0xFFC0392B); c.drawRect(px - 7, py - 34, px + 7, py - 32, p);
        c.drawRect(px - facing * 7 - (facing > 0 ? 5 : 0), py - 34, px - facing * 7 + (facing > 0 ? 0 : 5), py - 31, p);
        p.setColor(0xFF7F8C8D);
        p.setStrokeWidth(4f);
        c.drawLine(px, py - 20, px + aimX * 16f, py - 20 + aimY * 16f, p);
    }

    void drawEnemy(Canvas c, Enemy e) {
        boolean fl = e.flash > 0;
        p.setStyle(Paint.Style.FILL);
        if (e.type == 0) {
            float dir = px > e.x ? 1f : -1f;
            float leg = e.vx != 0 ? (float) Math.sin(frame * 0.3 + e.x) * 3f : 0f;
            p.setColor(0xFF3B3B3B);
            c.drawRect(e.x - 6 + leg, e.y - 13, e.x - 1 + leg, e.y, p);
            c.drawRect(e.x + 1 - leg, e.y - 13, e.x + 6 - leg, e.y, p);
            p.setColor(fl ? 0xFFFFFFFF : 0xFF6B8E23); c.drawRect(e.x - 8, e.y - 26, e.x + 8, e.y - 12, p);
            p.setColor(0xFFF5CBA7); c.drawCircle(e.x, e.y - 30, 5.5f, p);
            p.setColor(0xFF4B5320); c.drawRect(e.x - 6, e.y - 37, e.x + 6, e.y - 32, p);
            p.setColor(0xFF2B2B2B); p.setStrokeWidth(3.5f);
            c.drawLine(e.x, e.y - 20, e.x + dir * 15f, e.y - 20, p);
        } else if (e.type == 1) {
            p.setColor(fl ? 0xFFFFFFFF : 0xFF566573); c.drawRect(e.x - 13, e.y - 10, e.x + 13, e.y, p);
            p.setColor(fl ? 0xFFFFFFFF : 0xFF7F8C8D); c.drawCircle(e.x, e.y - 12, 9, p);
            float ang = (float) Math.atan2(py - 20f - (e.y - 14f), px - e.x);
            p.setColor(0xFF212F3C); p.setStrokeWidth(4f);
            c.drawLine(e.x, e.y - 14, e.x + (float) Math.cos(ang) * 16f, e.y - 14 + (float) Math.sin(ang) * 16f, p);
            p.setColor(0xFFFF5252); c.drawCircle(e.x, e.y - 13, 2.5f, p);
        } else {
            float left = e.x - e.w / 2f, top = e.y - e.h;
            p.setColor(fl ? 0xFFFFFFFF : 0xFF34495E); c.drawRect(left, top, left + e.w, e.y, p);
            p.setColor(0xFF2C3E50);
            c.drawRect(left - 8, top + 14, left, top + 30, p);
            c.drawRect(left - 8, top + 44, left, top + 60, p);
            c.drawRect(left, top, left + e.w, top + 10, p);
            c.drawRect(left, e.y - 12, left + e.w, e.y, p);
            p.setColor(0xFF1B2631);
            c.drawRect(left + 6, top + 18, left + e.w - 6, top + 22, p);
            c.drawRect(left + 6, top + 70, left + e.w - 6, top + 74, p);
            float pulse = 10f + (float) Math.sin(frame * 0.2) * 2f;
            p.setColor(0xFFFF1744); c.drawCircle(e.x - 6, top + 46, pulse, p);
            p.setColor(0xFFFFCDD2); c.drawCircle(e.x - 6, top + 46, pulse * 0.4f, p);
            p.setColor(0xFF000000); c.drawRect(left, top - 10, left + e.w, top - 5, p);
            p.setColor(0xFFE53935); c.drawRect(left, top - 10, left + e.w * Math.max(0, e.hp) / 80f, top - 5, p);
        }
    }

    void drawHud(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float ts = H * 0.05f;
        p.setTextSize(ts);
        p.setTextAlign(Paint.Align.LEFT);

        if (state == PLAY || state == WIN || state == OVER) {
            p.setColor(0xFFFFFFFF);
            c.drawText("LIVES " + lives, ts * 0.8f, ts * 1.4f, p);
            c.drawText("SCORE " + score, ts * 0.8f + W * 0.2f, ts * 1.4f, p);
            String wn = weapon == 1 ? "SPREAD" : (weapon == 2 ? "RAPID" : "BLASTER");
            c.drawText(wn, ts * 0.8f + W * 0.45f, ts * 1.4f, p);
            // progress bar
            float bw = W * 0.2f, bx = W - bw - ts, by = ts * 0.9f;
            p.setColor(0x66FFFFFF); c.drawRect(bx, by, bx + bw, by + ts * 0.4f, p);
            p.setColor(0xFF66BB6A); c.drawRect(bx, by, bx + bw * Math.min(1f, Math.max(0f, px / LEVEL_LEN)), by + ts * 0.4f, p);
        }

        if (state == PLAY) {
            drawBtn(c, cLx, cLy, bLeft, 0);
            drawBtn(c, cRx, cRy, bRight, 1);
            drawBtn(c, cUx, cUy, bUp, 2);
            drawBtn(c, cJx, cJy, bJump, 3);
            drawBtn(c, cFx, cFy, bFire, 4);
        } else {
            p.setColor(0xAA000000);
            c.drawRect(0, 0, W, H, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xFFFFB300);
            p.setTextSize(H * 0.14f);
            String title = state == TITLE ? "JUNGLE STRIKE" : (state == WIN ? "MISSION COMPLETE" : "GAME OVER");
            c.drawText(title, W / 2f, H * 0.38f, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(H * 0.055f);
            if (state != TITLE) c.drawText("SCORE " + score, W / 2f, H * 0.52f, p);
            if (state == TITLE) {
                p.setTextSize(H * 0.045f);
                c.drawText("< > move    ^ aim up    JUMP    FIRE", W / 2f, H * 0.54f, p);
                c.drawText("Grab S = spread, R = rapid, L = extra life. Beat the boss at the end.", W / 2f, H * 0.62f, p);
            }
            p.setTextSize(H * 0.06f);
            if ((frame / 30) % 2 == 0) c.drawText("TAP TO START", W / 2f, H * 0.80f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    void drawBtn(Canvas c, float cx, float cy, boolean down, int kind) {
        float r = bs * 0.6f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(down ? 0x88FFFFFF : 0x44FFFFFF);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        if (kind <= 2) {
            path.reset();
            if (kind == 0) {
                path.moveTo(cx + r * 0.3f, cy - r * 0.4f); path.lineTo(cx - r * 0.35f, cy); path.lineTo(cx + r * 0.3f, cy + r * 0.4f);
            } else if (kind == 1) {
                path.moveTo(cx - r * 0.3f, cy - r * 0.4f); path.lineTo(cx + r * 0.35f, cy); path.lineTo(cx - r * 0.3f, cy + r * 0.4f);
            } else {
                path.moveTo(cx - r * 0.4f, cy + r * 0.3f); path.lineTo(cx, cy - r * 0.35f); path.lineTo(cx + r * 0.4f, cy + r * 0.3f);
            }
            path.close();
            c.drawPath(path, p);
        } else {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(r * 0.5f);
            c.drawText(kind == 3 ? "JUMP" : "FIRE", cx, cy + r * 0.18f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}