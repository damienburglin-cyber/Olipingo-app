package fr.olipingo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/* The pedometer Damien starts himself ("Je pars marcher") and stops ("J'arrête"), never on its own.
   While it runs, a foreground "health" service listens to the phone's step counter (a low-power chip)
   and shows an ongoing notification. Steps are kept minute by minute; a minute with at least 50 steps (0.2.3, was 60: about 2 km/h for Damien)
   is an "active" minute (pauses do not count). It stops by itself after 60 minutes without an active
   minute, and at the end of the day. A finished walk is kept in "pending" until the artifact has
   written it into the day's sessions (bridge commands "walks" and "walksAck").
   0.2.2: the walk also keeps the last counter value read ("c") and when ("ct"). The step counter chip
   keeps counting when Android or Damien closes the app; when the service comes back (the app is
   reopened), the steps taken meanwhile are added at about 100 steps a minute, ending at the moment
   they are read. Steps taken after the walk's day has ended are not added. */
public class PedometerService extends Service implements SensorEventListener {

    static final String ACTION_START = "fr.olipingo.app.PODO_START";
    static final String ACTION_STOP = "fr.olipingo.app.PODO_STOP";
    static final int ACTIVE = 50;                    /* steps in a minute to call it active (about 2 km/h at 1.92 m) */
    static final long IDLE_STOP = 60 * 60 * 1000L;   /* auto stop after 60 min without an active minute */
    static final int MIN_KEEP = 3;                   /* walks under 3 active minutes are not kept */
    private static final String CHANNEL = "marche";
    private static final int NOTE_RUN = 41, NOTE_DONE = 42;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private float last = -1;
    private JSONObject cur;
    private final Runnable tick = new Runnable() {
        @Override public void run() { onTick(); h.postDelayed(this, 60 * 1000L); }
    };

    /* ---------- Storage shared with MainActivity ---------- */

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("podo", MODE_PRIVATE); }

    static JSONObject current(Context c) {
        try { String s = prefs(c).getString("cur", null); return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    static JSONArray pending(Context c) {
        try { return new JSONArray(prefs(c).getString("pending", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void ack(Context c, JSONArray ids) {
        JSONArray p = pending(c), keep = new JSONArray();
        for (int i = 0; i < p.length(); i++) {
            JSONObject w = p.optJSONObject(i);
            if (w == null) continue;
            boolean done = false;
            for (int j = 0; j < ids.length(); j++) if (w.optString("id").equals(ids.optString(j))) done = true;
            if (!done) keep.put(w);
        }
        prefs(c).edit().putString("pending", keep.toString()).apply();
    }

    static int steps(JSONObject w) { int n = 0; JSONArray m = w.optJSONArray("m"); if (m != null) for (int i = 0; i < m.length(); i++) n += m.optInt(i); return n; }

    static int active(JSONObject w) { int n = 0; JSONArray m = w.optJSONArray("m"); if (m != null) for (int i = 0; i < m.length(); i++) if (m.optInt(i) >= ACTIVE) n++; return n; }

    static int lastActive(JSONObject w) { JSONArray m = w.optJSONArray("m"); if (m != null) for (int i = m.length() - 1; i >= 0; i--) if (m.optInt(i) >= ACTIVE) return i; return -1; }

    /* ---------- Service ---------- */

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) { finish(-1); return START_NOT_STICKY; }
        cur = current(this);
        if (cur == null) {
            try {
                long now = System.currentTimeMillis();
                cur = new JSONObject().put("id", "w" + now).put("debut", now).put("m", new JSONArray());
                save();
            } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        }
        Notification n = running();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        else startForeground(NOTE_RUN, n);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) { finish(-1); return START_NOT_STICKY; }
        sm.unregisterListener(this);
        last = cur.has("c") ? (float) cur.optDouble("c") : -1;   /* back after the app was closed: catch up */
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 20 * 1000 * 1000);
        h.removeCallbacks(tick);
        h.postDelayed(tick, 60 * 1000L);
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (cur == null) return;
        float v = e.values[0];
        long at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1000000L;
        long debut = cur.optLong("debut"), prev = cur.optLong("ct", at);
        try {
            if (last < 0) { last = v; mark(v, at); return; }   /* steps taken before the start are not counted */
            float d = v < last ? v : v - last;                 /* the counter restarts at 0 after a reboot */
            last = v;
            if (at >= dayEnd(debut)) { mark(v, at); h.post(this::onTick); return; }   /* the walk's day is over */
            if (d > 0) {
                int n = Math.round(d), idx = (int) Math.max(0, (at - debut) / 60000L);
                JSONArray m = cur.getJSONArray("m");
                while (m.length() <= idx) m.put(0);
                if (at - prev > 2 * 60000L && n > ACTIVE) {
                    /* Steps counted while the app was closed: about 100 a minute, ending now. */
                    int from = (int) Math.max(0, (prev - debut) / 60000L);
                    int k = Math.max(1, Math.min(idx - from + 1, (int) Math.ceil(n / 100.0)));
                    for (int i = 0, left = n; i < k; i++) {
                        int j = idx - k + 1 + i, s = left / (k - i);
                        m.put(j, m.optInt(j) + s); left -= s;
                    }
                } else m.put(idx, m.optInt(idx) + n);
            }
            mark(v, at);
        } catch (Exception ignored) { }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void onTick() {
        if (cur == null) return;
        long now = System.currentTimeMillis(), debut = cur.optLong("debut");
        int la = lastActive(cur);
        long lastAt = la < 0 ? debut : debut + (la + 1) * 60000L;
        if (now - lastAt >= IDLE_STOP) { finish(la < 0 ? debut : lastAt); return; }
        long end = dayEnd(debut);
        if (now >= end) { finish(end); return; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTE_RUN, running());
    }

    /* Ends the walk at "fin" (or now), keeps it if it has at least 3 active minutes, and stops the service. */
    private void finish(long fin) {
        JSONObject w = cur != null ? cur : current(this);
        if (sm != null) sm.unregisterListener(this);
        h.removeCallbacks(tick);
        prefs(this).edit().remove("cur").apply();
        cur = null;
        String done = null;
        if (w != null) {
            try {
                long debut = w.optLong("debut"), end = fin > 0 ? fin : System.currentTimeMillis();
                int keepMin = (int) Math.max(1, Math.ceil((end - debut) / 60000.0));
                JSONArray m = w.optJSONArray("m"), mm = new JSONArray();
                for (int i = 0; m != null && i < Math.min(m.length(), keepMin); i++) mm.put(m.optInt(i));
                w.put("m", mm).put("fin", end);
                int act = active(w);
                if (act >= MIN_KEEP) {
                    JSONArray p = pending(this); p.put(w);
                    prefs(this).edit().putString("pending", p.toString()).apply();
                    done = "Marche enregistrée · " + act + " min actives";
                } else done = "Moins de 3 min de marche : rien n'est gardé";
            } catch (Exception ignored) { }
        }
        stopForeground(true);
        if (done != null) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_DONE, base().setContentTitle(done).setOngoing(false).setAutoCancel(true).build());
        }
        stopSelf();
    }

    private void save() { prefs(this).edit().putString("cur", cur.toString()).apply(); }

    /* Keeps the last counter value and its time with the walk, so steps can be caught up after a close. */
    private void mark(float v, long at) throws org.json.JSONException { cur.put("c", (double) v).put("ct", at); save(); }

    static long dayEnd(long debut) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(debut);
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Override
    public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    /* ---------- Notifications ---------- */

    private Notification.Builder base() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Marche dehors", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Le compteur de pas pendant une marche lancée dans Olipingo");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else b = new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return b.setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setOnlyAlertOnce(true);
    }

    private Notification running() {
        long debut = cur == null ? System.currentTimeMillis() : cur.optLong("debut");
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(debut);
        String since = c.get(Calendar.HOUR_OF_DAY) + " h " + String.format(java.util.Locale.FRANCE, "%02d", c.get(Calendar.MINUTE));
        int act = cur == null ? 0 : active(cur), st = cur == null ? 0 : steps(cur);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PedometerService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return base().setContentTitle("Olipingo compte tes pas")
            .setContentText(act + " min actives · " + st + " pas · depuis " + since)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
            .build();
    }
}
