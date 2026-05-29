package com.example.alarmclockapp;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView currentTimeText;
    private TextView shakeCounterText;
    private LinearLayout alarmListLayout;
    private LinearLayout ringingPanel;
    private Button addAlarmButton;

    private final Handler handler = new Handler();

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;

    private boolean isRinging = false;

    private int shakeCount = 0;
    private final int REQUIRED_SHAKES = 5;

    /*
       Это уже не резкий shake, а более мягкое движение.
       Принцип похож на шагомер:
       считаем не удар, а заметное изменение ускорения.
    */
    private float lastAcceleration = SensorManager.GRAVITY_EARTH;
    private float currentAcceleration = SensorManager.GRAVITY_EARTH;
    private float accelerationDelta = 0.0f;
    private long lastStepShakeTime = 0;

    private static final float STEP_SHAKE_THRESHOLD = 1.15f;
    private static final int STEP_SHAKE_DELAY_MS = 300;

    private SharedPreferences preferences;
    private static final String PREFS_NAME = "alarms_storage";
    private static final String ALARMS_KEY = "alarms";

    private static final int RINGTONE_REQUEST_CODE = 777;

    private Uri selectedRingtoneUriForDialog = null;
    private TextView selectedRingtoneTextForDialog = null;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            currentTimeText.setText("Сейчас: " + timeFormat.format(Calendar.getInstance().getTime()));
            handler.postDelayed(this, 1000);
        }
    };

    private static class AlarmItem {
        long id;
        int hour;
        int minute;
        int daysMask;
        String ringtoneUri;

        AlarmItem(long id, int hour, int minute, int daysMask, String ringtoneUri) {
            this.id = id;
            this.hour = hour;
            this.minute = minute;
            this.daysMask = daysMask;
            this.ringtoneUri = ringtoneUri;
        }

        String toStorageString() {
            return id + "|" + hour + "|" + minute + "|" + daysMask + "|" + ringtoneUri;
        }

        static AlarmItem fromStorageString(String value) {
            try {
                String[] parts = value.split("\\|", -1);
                long id = Long.parseLong(parts[0]);
                int hour = Integer.parseInt(parts[1]);
                int minute = Integer.parseInt(parts[2]);
                int daysMask = Integer.parseInt(parts[3]);
                String ringtoneUri = parts.length >= 5 ? parts[4] : "";

                return new AlarmItem(id, hour, minute, daysMask, ringtoneUri);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        makeActivityVisibleOnLockScreen();

        setContentView(R.layout.activity_main);

        currentTimeText = findViewById(R.id.currentTimeText);
        shakeCounterText = findViewById(R.id.shakeCounterText);
        alarmListLayout = findViewById(R.id.alarmListLayout);
        ringingPanel = findViewById(R.id.ringingPanel);
        addAlarmButton = findViewById(R.id.addAlarmButton);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        addAlarmButton.setOnClickListener(v -> showAlarmDialog(null));

        handler.post(clockRunnable);

        checkExactAlarmPermission();
        renderAlarmList();

        handleAlarmIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAlarmIntent(intent);
    }

    private void handleAlarmIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("ALARM_RING", false)) {
            long alarmId = intent.getLongExtra("ALARM_ID", -1);

            AlarmItem alarm = findAlarmById(alarmId);

            if (alarm != null) {
                if (alarm.daysMask == 0) {
                    removeAlarmFromStorage(alarm.id);
                } else {
                    scheduleAlarm(alarm);
                }

                renderAlarmList();
                startAlarm(alarm.ringtoneUri);
            } else {
                startAlarm("");
            }
        }
    }

    private void showAlarmDialog(AlarmItem alarmToEdit) {
        Calendar now = Calendar.getInstance();

        int startHour = alarmToEdit == null ? now.get(Calendar.HOUR_OF_DAY) : alarmToEdit.hour;
        int startMinute = alarmToEdit == null ? now.get(Calendar.MINUTE) : alarmToEdit.minute;

        selectedRingtoneUriForDialog = getDefaultAlarmUri();

        if (alarmToEdit != null && alarmToEdit.ringtoneUri != null && !alarmToEdit.ringtoneUri.isEmpty()) {
            selectedRingtoneUriForDialog = Uri.parse(alarmToEdit.ringtoneUri);
        }

        final int[] selectedHour = {startHour};
        final int[] selectedMinute = {startMinute};

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(40, 20, 40, 10);

        TextView timeText = new TextView(this);
        timeText.setText("Время: " + formatHourMinute(selectedHour[0], selectedMinute[0]));
        timeText.setTextSize(20);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, 10, 0, 20);

        Button chooseTimeButton = new Button(this);
        chooseTimeButton.setText("Выбрать время");

        chooseTimeButton.setOnClickListener(v -> {
            TimePickerDialog timePickerDialog = new TimePickerDialog(
                    this,
                    (view, hourOfDay, minute) -> {
                        selectedHour[0] = hourOfDay;
                        selectedMinute[0] = minute;
                        timeText.setText("Время: " + formatHourMinute(selectedHour[0], selectedMinute[0]));
                    },
                    selectedHour[0],
                    selectedMinute[0],
                    true
            );

            timePickerDialog.show();
        });

        TextView repeatTitle = new TextView(this);
        repeatTitle.setText("Повторять по дням:");
        repeatTitle.setTextSize(18);
        repeatTitle.setPadding(0, 25, 0, 10);

        CheckBox monday = new CheckBox(this);
        CheckBox tuesday = new CheckBox(this);
        CheckBox wednesday = new CheckBox(this);
        CheckBox thursday = new CheckBox(this);
        CheckBox friday = new CheckBox(this);
        CheckBox saturday = new CheckBox(this);
        CheckBox sunday = new CheckBox(this);

        monday.setText("Понедельник");
        tuesday.setText("Вторник");
        wednesday.setText("Среда");
        thursday.setText("Четверг");
        friday.setText("Пятница");
        saturday.setText("Суббота");
        sunday.setText("Воскресенье");

        if (alarmToEdit != null) {
            monday.setChecked((alarmToEdit.daysMask & 1) != 0);
            tuesday.setChecked((alarmToEdit.daysMask & 2) != 0);
            wednesday.setChecked((alarmToEdit.daysMask & 4) != 0);
            thursday.setChecked((alarmToEdit.daysMask & 8) != 0);
            friday.setChecked((alarmToEdit.daysMask & 16) != 0);
            saturday.setChecked((alarmToEdit.daysMask & 32) != 0);
            sunday.setChecked((alarmToEdit.daysMask & 64) != 0);
        }

        Button chooseRingtoneButton = new Button(this);
        chooseRingtoneButton.setText("Выбрать мелодию");

        selectedRingtoneTextForDialog = new TextView(this);
        selectedRingtoneTextForDialog.setText("Мелодия: " + getRingtoneTitle(selectedRingtoneUriForDialog));
        selectedRingtoneTextForDialog.setTextSize(16);
        selectedRingtoneTextForDialog.setPadding(0, 20, 0, 10);

        chooseRingtoneButton.setOnClickListener(v -> openRingtonePicker());

        dialogLayout.addView(timeText);
        dialogLayout.addView(chooseTimeButton);
        dialogLayout.addView(repeatTitle);

        dialogLayout.addView(monday);
        dialogLayout.addView(tuesday);
        dialogLayout.addView(wednesday);
        dialogLayout.addView(thursday);
        dialogLayout.addView(friday);
        dialogLayout.addView(saturday);
        dialogLayout.addView(sunday);

        dialogLayout.addView(selectedRingtoneTextForDialog);
        dialogLayout.addView(chooseRingtoneButton);

        String dialogTitle = alarmToEdit == null ? "Добавить будильник" : "Редактировать будильник";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(dialogLayout)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

            saveButton.setOnClickListener(v -> {
                int daysMask = 0;

                if (monday.isChecked()) daysMask |= 1;
                if (tuesday.isChecked()) daysMask |= 2;
                if (wednesday.isChecked()) daysMask |= 4;
                if (thursday.isChecked()) daysMask |= 8;
                if (friday.isChecked()) daysMask |= 16;
                if (saturday.isChecked()) daysMask |= 32;
                if (sunday.isChecked()) daysMask |= 64;

                String ringtoneString = selectedRingtoneUriForDialog == null
                        ? ""
                        : selectedRingtoneUriForDialog.toString();

                if (alarmToEdit == null) {
                    AlarmItem newAlarm = new AlarmItem(
                            System.currentTimeMillis(),
                            selectedHour[0],
                            selectedMinute[0],
                            daysMask,
                            ringtoneString
                    );

                    saveAlarm(newAlarm);
                    scheduleAlarm(newAlarm);

                    Toast.makeText(this, "Будильник добавлен", Toast.LENGTH_SHORT).show();
                } else {
                    cancelAlarm(alarmToEdit);

                    AlarmItem updatedAlarm = new AlarmItem(
                            alarmToEdit.id,
                            selectedHour[0],
                            selectedMinute[0],
                            daysMask,
                            ringtoneString
                    );

                    updateAlarm(updatedAlarm);
                    scheduleAlarm(updatedAlarm);

                    Toast.makeText(this, "Будильник изменён", Toast.LENGTH_SHORT).show();
                }

                renderAlarmList();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void openRingtonePicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);

        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Выберите мелодию будильника");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUriForDialog);

        startActivityForResult(intent, RINGTONE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RINGTONE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);

            if (uri != null) {
                selectedRingtoneUriForDialog = uri;

                if (selectedRingtoneTextForDialog != null) {
                    selectedRingtoneTextForDialog.setText(
                            "Мелодия: " + getRingtoneTitle(selectedRingtoneUriForDialog)
                    );
                }
            }
        }
    }

    private void scheduleAlarm(AlarmItem alarm) {
        long nextTime = calculateNextAlarmTime(alarm);

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("ALARM_RING", true);
        intent.putExtra("ALARM_ID", alarm.id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                getRequestCode(alarm.id),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (alarmManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTime,
                    pendingIntent
            );
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    nextTime,
                    pendingIntent
            );
        }
    }

    private long calculateNextAlarmTime(AlarmItem alarm) {
        Calendar now = Calendar.getInstance();

        Calendar candidate = Calendar.getInstance();
        candidate.set(Calendar.HOUR_OF_DAY, alarm.hour);
        candidate.set(Calendar.MINUTE, alarm.minute);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);

        if (alarm.daysMask == 0) {
            if (candidate.getTimeInMillis() <= now.getTimeInMillis()) {
                candidate.add(Calendar.DAY_OF_MONTH, 1);
            }

            return candidate.getTimeInMillis();
        }

        for (int i = 0; i < 8; i++) {
            Calendar check = (Calendar) candidate.clone();
            check.add(Calendar.DAY_OF_MONTH, i);

            int dayBit = getDayBit(check.get(Calendar.DAY_OF_WEEK));

            if ((alarm.daysMask & dayBit) != 0 && check.getTimeInMillis() > now.getTimeInMillis()) {
                return check.getTimeInMillis();
            }
        }

        candidate.add(Calendar.DAY_OF_MONTH, 1);
        return candidate.getTimeInMillis();
    }

    private int getDayBit(int calendarDayOfWeek) {
        switch (calendarDayOfWeek) {
            case Calendar.MONDAY:
                return 1;
            case Calendar.TUESDAY:
                return 2;
            case Calendar.WEDNESDAY:
                return 4;
            case Calendar.THURSDAY:
                return 8;
            case Calendar.FRIDAY:
                return 16;
            case Calendar.SATURDAY:
                return 32;
            case Calendar.SUNDAY:
                return 64;
            default:
                return 0;
        }
    }

    private void cancelAlarm(AlarmItem alarm) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                getRequestCode(alarm.id),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
    }

    private int getRequestCode(long id) {
        return (int) (id % Integer.MAX_VALUE);
    }

    private void saveAlarm(AlarmItem alarm) {
        ArrayList<AlarmItem> alarms = getSavedAlarms();
        alarms.add(alarm);
        saveAllAlarms(alarms);
    }

    private void updateAlarm(AlarmItem updatedAlarm) {
        ArrayList<AlarmItem> alarms = getSavedAlarms();

        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).id == updatedAlarm.id) {
                alarms.set(i, updatedAlarm);
                break;
            }
        }

        saveAllAlarms(alarms);
    }

    private void removeAlarmFromStorage(long alarmId) {
        ArrayList<AlarmItem> alarms = getSavedAlarms();

        for (int i = alarms.size() - 1; i >= 0; i--) {
            if (alarms.get(i).id == alarmId) {
                alarms.remove(i);
            }
        }

        saveAllAlarms(alarms);
    }

    private void saveAllAlarms(ArrayList<AlarmItem> alarms) {
        Set<String> alarmsSet = new HashSet<>();

        for (AlarmItem alarm : alarms) {
            alarmsSet.add(alarm.toStorageString());
        }

        preferences.edit().putStringSet(ALARMS_KEY, alarmsSet).apply();
    }

    private ArrayList<AlarmItem> getSavedAlarms() {
        Set<String> alarmsSet = preferences.getStringSet(ALARMS_KEY, new HashSet<>());
        ArrayList<AlarmItem> alarms = new ArrayList<>();

        for (String alarmString : alarmsSet) {
            AlarmItem alarm = AlarmItem.fromStorageString(alarmString);

            if (alarm != null) {
                alarms.add(alarm);
            }
        }

        Collections.sort(alarms, (a, b) -> {
            if (a.hour != b.hour) return a.hour - b.hour;
            return a.minute - b.minute;
        });

        return alarms;
    }

    private AlarmItem findAlarmById(long id) {
        ArrayList<AlarmItem> alarms = getSavedAlarms();

        for (AlarmItem alarm : alarms) {
            if (alarm.id == id) {
                return alarm;
            }
        }

        return null;
    }

    private void renderAlarmList() {
        alarmListLayout.removeAllViews();

        ArrayList<AlarmItem> alarms = getSavedAlarms();

        if (alarms.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("Будильников пока нет");
            emptyText.setTextColor(0xFFBFD7EA);
            emptyText.setTextSize(18);
            emptyText.setGravity(Gravity.CENTER);
            alarmListLayout.addView(emptyText);
            return;
        }

        for (AlarmItem alarm : alarms) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(16, 16, 16, 16);
            card.setBackgroundColor(0xFF1B2A41);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );

            cardParams.setMargins(0, 0, 0, 16);
            card.setLayoutParams(cardParams);

            TextView alarmTimeText = new TextView(this);
            alarmTimeText.setText(formatHourMinute(alarm.hour, alarm.minute));
            alarmTimeText.setTextColor(0xFFFFFFFF);
            alarmTimeText.setTextSize(26);
            alarmTimeText.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView repeatText = new TextView(this);
            repeatText.setText("Повтор: " + getDaysText(alarm.daysMask));
            repeatText.setTextColor(0xFFBFD7EA);
            repeatText.setTextSize(16);
            repeatText.setPadding(0, 6, 0, 6);

            TextView melodyText = new TextView(this);
            Uri ringtoneUri = alarm.ringtoneUri == null || alarm.ringtoneUri.isEmpty()
                    ? getDefaultAlarmUri()
                    : Uri.parse(alarm.ringtoneUri);

            melodyText.setText("Мелодия: " + getRingtoneTitle(ringtoneUri));
            melodyText.setTextColor(0xFFBFD7EA);
            melodyText.setTextSize(15);
            melodyText.setPadding(0, 0, 0, 12);

            LinearLayout buttonsRow = new LinearLayout(this);
            buttonsRow.setOrientation(LinearLayout.HORIZONTAL);

            Button editButton = new Button(this);
            editButton.setText("Редактировать");

            Button deleteButton = new Button(this);
            deleteButton.setText("Удалить");

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1
            );

            editButton.setLayoutParams(buttonParams);
            deleteButton.setLayoutParams(buttonParams);

            editButton.setOnClickListener(v -> showAlarmDialog(alarm));

            deleteButton.setOnClickListener(v -> {
                cancelAlarm(alarm);
                removeAlarmFromStorage(alarm.id);
                renderAlarmList();

                Toast.makeText(this, "Будильник удалён", Toast.LENGTH_SHORT).show();
            });

            buttonsRow.addView(editButton);
            buttonsRow.addView(deleteButton);

            card.addView(alarmTimeText);
            card.addView(repeatText);
            card.addView(melodyText);
            card.addView(buttonsRow);

            alarmListLayout.addView(card);
        }
    }

    private void startAlarm(String ringtoneUriString) {
        if (isRinging) return;

        isRinging = true;
        shakeCount = 0;

        lastAcceleration = SensorManager.GRAVITY_EARTH;
        currentAcceleration = SensorManager.GRAVITY_EARTH;
        accelerationDelta = 0.0f;
        lastStepShakeTime = 0;

        ringingPanel.setVisibility(View.VISIBLE);
        shakeCounterText.setText("Движения: 0 / " + REQUIRED_SHAKES);

        startSound(ringtoneUriString);
        startVibration();

        Toast.makeText(
                this,
                "Будильник! Сделайте 5 движений телефоном как при ходьбе",
                Toast.LENGTH_LONG
        ).show();
    }

    private void stopAlarm() {
        isRinging = false;
        shakeCount = 0;

        ringingPanel.setVisibility(View.GONE);

        stopSound();
        stopVibration();

        Toast.makeText(this, "Будильник отключён", Toast.LENGTH_SHORT).show();
    }

    private void startSound(String ringtoneUriString) {
        try {
            Uri alarmUri;

            if (ringtoneUriString != null && !ringtoneUriString.isEmpty()) {
                alarmUri = Uri.parse(ringtoneUriString);
            } else {
                alarmUri = getDefaultAlarmUri();
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alarmUri);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                );
            }

            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();

        } catch (Exception e) {
            Toast.makeText(this, "Не удалось воспроизвести мелодию", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSound() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }

                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void startVibration() {
        if (vibrator == null) return;

        long[] pattern = {0, 700, 400, 700, 400};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRinging) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            lastAcceleration = currentAcceleration;
            currentAcceleration = (float) Math.sqrt(x * x + y * y + z * z);

            accelerationDelta = currentAcceleration - lastAcceleration;

            /*
               Фильтр похож на шагомер:
               движение считается только если изменение ускорения
               достаточно заметное, но порог ниже, чем для резкой тряски.
            */
            if (accelerationDelta > STEP_SHAKE_THRESHOLD) {
                long now = System.currentTimeMillis();

                if (now - lastStepShakeTime > STEP_SHAKE_DELAY_MS) {
                    lastStepShakeTime = now;
                    shakeCount++;

                    shakeCounterText.setText(
                            "Движения: " + shakeCount + " / " + REQUIRED_SHAKES
                    );

                    if (shakeCount >= REQUIRED_SHAKES) {
                        stopAlarm();
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Не используется
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(
                    this,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacks(clockRunnable);
        stopSound();
        stopVibration();
    }

    private String formatHourMinute(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private String getDaysText(int daysMask) {
        if (daysMask == 0) {
            return "один раз";
        }

        ArrayList<String> days = new ArrayList<>();

        if ((daysMask & 1) != 0) days.add("Пн");
        if ((daysMask & 2) != 0) days.add("Вт");
        if ((daysMask & 4) != 0) days.add("Ср");
        if ((daysMask & 8) != 0) days.add("Чт");
        if ((daysMask & 16) != 0) days.add("Пт");
        if ((daysMask & 32) != 0) days.add("Сб");
        if ((daysMask & 64) != 0) days.add("Вс");

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < days.size(); i++) {
            builder.append(days.get(i));

            if (i < days.size() - 1) {
                builder.append(", ");
            }
        }

        return builder.toString();
    }

    private Uri getDefaultAlarmUri() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }

        return uri;
    }

    private String getRingtoneTitle(Uri uri) {
        try {
            if (uri == null) {
                return "Стандартная";
            }

            Ringtone ringtone = RingtoneManager.getRingtone(this, uri);

            if (ringtone != null) {
                return ringtone.getTitle(this);
            }
        } catch (Exception ignored) {
        }

        return "Выбранная мелодия";
    }

    private void makeActivityVisibleOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
    }

    private void checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(
                        this,
                        "Разрешите точные будильники в настройках приложения",
                        Toast.LENGTH_LONG
                ).show();

                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                startActivity(intent);
            }
        }
    }
}