package com.glassradio;

import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class StationManager {

    private static final String TAG = "GlassRadio";
    private static final String STATIONS_FILE = "glass-radio/stations.json";

    public static List<RadioStation> loadStations() {
        List<RadioStation> stations = loadFromFile();
        if (stations != null && !stations.isEmpty()) {
            Log.d(TAG, "Loaded " + stations.size() + " stations from file");
            return stations;
        }
        Log.d(TAG, "Using default stations");
        return getDefaults();
    }

    private static List<RadioStation> loadFromFile() {
        File file = new File(Environment.getExternalStorageDirectory(), STATIONS_FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            List<RadioStation> stations = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                stations.add(new RadioStation(
                        obj.getString("name"),
                        obj.optString("genre", ""),
                        obj.getString("url")
                ));
            }
            return stations;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load stations from file", e);
            return null;
        }
    }

    private static List<RadioStation> getDefaults() {
        List<RadioStation> stations = new ArrayList<>();
        stations.add(new RadioStation("Groove Salad", "Ambient", "https://ice1.somafm.com/groovesalad-128-mp3"));
        stations.add(new RadioStation("Drone Zone", "Ambient", "https://ice1.somafm.com/dronezone-128-mp3"));
        stations.add(new RadioStation("DEF CON Radio", "Electronic", "https://ice1.somafm.com/defcon-128-mp3"));
        stations.add(new RadioStation("Space Station", "Ambient", "https://ice1.somafm.com/spacestation-128-mp3"));
        stations.add(new RadioStation("Secret Agent", "Lounge", "https://ice1.somafm.com/secretagent-128-mp3"));
        return stations;
    }
}
