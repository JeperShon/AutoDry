package com.example.autodry;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.*;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import android.app.AlertDialog;
import android.content.DialogInterface;

public class MainActivity extends AppCompatActivity {
    // Permission request codes
    private static final int RC_LOCATION         = 3001;
    private static final int RC_BLUETOOTH        = 3002;
    private static final int RC_ENABLE_BT        = 4001;
    private static final int RC_CHECK_SETTINGS   = 5001; // for GPS settings resolution

    // Bluetooth constants
    private static final String ARDUINO_MAC      = "00:11:22:33:AA:BB";
    private static final UUID   BT_UUID          = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Weather API
    private static final String WEATHER_API_KEY  = "9804a0d036f48477b21cb5c3087c8a95";

    // UI & state
    private BluetoothAdapter    btAdapter;
    private BluetoothSocket     btSocket;

    private TextView            tvBtStatus, tvPowerState;
    private View                vPowerDot;
    private Button              btnOn, btnOff;

    private TextView            tvCity, tvTemp;
    private ImageView           ivWeatherIcon;

    private Button btnChooseDevice;
    private TextView tvChosenDevice;

    // store the MAC the user picked
    private String selectedDeviceMac = null;

    private FusedLocationProviderClient fusedLocationClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1) Wire up UI
        tvBtStatus     = findViewById(R.id.tvBtStatus);
        vPowerDot      = findViewById(R.id.vPowerDot);
        tvPowerState   = findViewById(R.id.tvPowerState);
        btnOn          = findViewById(R.id.btnOn);
        btnOff         = findViewById(R.id.btnOff);
        tvCity         = findViewById(R.id.tvCity);
        tvTemp         = findViewById(R.id.tvTemp);
        ivWeatherIcon  = findViewById(R.id.ivWeatherIcon);
        btnChooseDevice    = findViewById(R.id.btnChooseDevice);
        tvChosenDevice     = findViewById(R.id.tvChosenDevice);
        btnChooseDevice.setOnClickListener(v -> showPairedDevicesDialog());


        // 2) Bluetooth setup
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        btnOn.setOnClickListener(v -> sendToArduino("ON"));
        btnOff.setOnClickListener(v -> sendToArduino("OFF"));
        updatePowerUI(false);

        // 3) Location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 4) Kick off permission flows
        checkLocationPermission();
        checkBluetoothPermission();
    }

    @SuppressLint("MissingPermission")
    private void showPairedDevicesDialog() {
        if (btAdapter == null) return;

        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        if (paired.isEmpty()) {
            Toast.makeText(this, "No paired devices", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> names = new ArrayList<>();
        final List<String> macs = new ArrayList<>();
        for (BluetoothDevice d : paired) {
            names.add(d.getName() + "\n" + d.getAddress());
            macs.add(d.getAddress());
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose a Bluetooth Device")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedDeviceMac = macs.get(which);
                        tvChosenDevice.setText(names.get(which));
                        // Re-connect now that we have a new MAC
                        startBluetoothConnectFlow();
                    }
                })
                .show();
    }



    // ——— PERMISSION FLOWS ———

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    RC_LOCATION);
        } else {
            startLocationAndWeatherFlow();
        }
    }

    private void checkBluetoothPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    RC_BLUETOOTH);
        } else {
            startBluetoothConnectFlow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] perms,
                                           @NonNull int[] results)
    {
        super.onRequestPermissionsResult(requestCode, perms, results);

        if (requestCode == RC_LOCATION) {
            if (allGranted(results)) {
                startLocationAndWeatherFlow();
            } else {
                Toast.makeText(this,
                        "Location permission required for weather",
                        Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == RC_BLUETOOTH) {
            if (allGranted(results)) {
                startBluetoothConnectFlow();
            } else {
                Toast.makeText(this,
                        "Bluetooth permission required for Arduino",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean allGranted(int[] results) {
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    // ——— LOCATION & WEATHER ———

    private void startLocationAndWeatherFlow() {
        LocationRequest req = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        LocationSettingsRequest settings = new LocationSettingsRequest.Builder()
                .addLocationRequest(req).build();
        SettingsClient client = LocationServices.getSettingsClient(this);
        client.checkLocationSettings(settings)
                .addOnSuccessListener(r -> fetchWeatherForCurrentLocation())
                .addOnFailureListener(e -> {
                    if (e instanceof ResolvableApiException) {
                        try {
                            ((ResolvableApiException)e)
                                    .startResolutionForResult(
                                            MainActivity.this,
                                            RC_CHECK_SETTINGS);
                        } catch (Exception ignored) {}
                    } else {
                        fetchWeatherForCurrentLocation();
                    }
                });
    }

    // ——— BLUETOOTH & ARDUINO ———
    @SuppressLint("MissingPermission")
    private void startBluetoothConnectFlow() {
        if (btAdapter == null) {
            tvBtStatus.setText("Bluetooth not supported");
            return;
        }
        if (!btAdapter.isEnabled()) {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    RC_ENABLE_BT);
        } else {
            new ConnectBluetoothTask().execute();
        }
    }

    @SuppressLint("MissingPermission")
    private void sendToArduino(String cmd) {
        if (btSocket != null && btSocket.isConnected()) {
            try {
                // DEBUG: log & toast the raw command
                Log.d("BT_SEND", "Sending → \"" + cmd + "\"");
                Toast.makeText(this, "Sending “" + cmd + "”", Toast.LENGTH_SHORT).show();

                OutputStream os = btSocket.getOutputStream();
                os.write((cmd + "\n").getBytes());
                os.flush();
                updatePowerUI("ON".equals(cmd));
            } catch (IOException e) {
                Toast.makeText(this, "Send failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
        }
    }

    private class ConnectBluetoothTask extends AsyncTask<Void,Void,Boolean> {
        @Override
        protected void onPreExecute() {
            tvBtStatus.setText("Connecting…");
        }

        @SuppressLint({"MissingPermission","NewApi"})
        @Override
        protected Boolean doInBackground(Void... v) {
            try {
                // 1) pick the MAC address
                String mac = (selectedDeviceMac != null)
                        ? selectedDeviceMac
                        : ARDUINO_MAC;

                // 2) get the device and open its socket
                BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
                btSocket = dev
                        .createRfcommSocketToServiceRecord(BT_UUID);

                // 3) actually connect (this can throw IOException)
                btSocket.connect();
                return true;

            } catch (SecurityException|IOException e) {
                // catch both the permission and IO errors here
                Log.e("BT", "Connect failed", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            tvBtStatus.setText(
                    ok
                            ? "Bluetooth: Connected"
                            : "Connection Error"
            );
        }
    }


    // ——— UI UPDATES ———

    private void updatePowerUI(boolean isOn) {
        int clr = isOn?R.color.onColor:R.color.offColor;
        int txt = isOn?R.string.power_on:R.string.power_off;
        vPowerDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, clr))
        );
        tvPowerState.setText(txt);
    }

    // ——— WEATHER TASK ———

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_CHECK_SETTINGS) {
            // user just handled the “Turn on GPS?” dialog
            fetchWeatherForCurrentLocation();
        }
        else if (requestCode == RC_ENABLE_BT) {
            // user handled the “Enable Bluetooth?” dialog
            startBluetoothConnectFlow();
        }
    }


    @SuppressLint("MissingPermission")
    private void fetchWeatherForCurrentLocation() {
        // Force one fresh high-accuracy GPS update
        LocationRequest req = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(1)
                .setInterval(0);

        fusedLocationClient.requestLocationUpdates(
                req,
                new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult res) {
                        fusedLocationClient.removeLocationUpdates(this);
                        Location loc = res.getLastLocation();

                        if (loc != null) {
                            double lat = loc.getLatitude();
                            double lon = loc.getLongitude();

                            // *** DEBUG TOAST & LOG ***
                            Toast.makeText(MainActivity.this,
                                    String.format(Locale.US,
                                            "DEBUG GPS → Lat=%.5f, Lon=%.5f", lat, lon),
                                    Toast.LENGTH_LONG).show();
                            Log.d("DEBUG_GPS",
                                    String.format("Lat=%.5f, Lon=%.5f", lat, lon));

                            new FetchWeatherTask().execute(lat, lon);
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "DEBUG GPS → location still null!",
                                    Toast.LENGTH_LONG).show();
                            new FetchWeatherTask().execute(14.6, 120.9);
                        }
                    }
                },
                null
        );
    }



    private class FetchWeatherTask
            extends AsyncTask<Double,Void,JSONObject>
    {
        @Override protected JSONObject doInBackground(Double... a) {
            String u = String.format(Locale.US,
                    "https://api.openweathermap.org/data/2.5/weather?"
                            + "lat=%.6f&lon=%.6f&units=metric&appid=%s",
                    a[0], a[1], WEATHER_API_KEY);
            try {
                HttpsURLConnection c = (HttpsURLConnection)
                        new URL(u).openConnection();
                BufferedReader r=new BufferedReader(
                        new InputStreamReader(c.getInputStream()));
                StringBuilder sb=new StringBuilder(); String line;
                while((line=r.readLine())!=null) sb.append(line);
                return new JSONObject(sb.toString());
            } catch(Exception e) {
                Log.e("WX","Error",e);
                return null;
            }
        }
        @Override protected void onPostExecute(JSONObject j) {
            if(j==null) return;
            try {
                tvCity.setText(j.getString("name"));
                double t=j.getJSONObject("main").getDouble("temp");
                tvTemp.setText(String.format(Locale.US,"%.1f°C",t));
                String icon=j.getJSONArray("weather")
                        .getJSONObject(0).getString("icon");
                new DownloadIconTask(ivWeatherIcon).execute(
                        "https://openweathermap.org/img/wn/"+icon+"@2x.png");
            } catch(Exception e) { Log.e("WX","Parse",e); }
        }
    }

    private static class DownloadIconTask
            extends AsyncTask<String,Void,android.graphics.Bitmap>
    {
        private final ImageView iv;
        DownloadIconTask(ImageView iv){this.iv=iv;}
        @Override protected android.graphics.Bitmap doInBackground(String... urls){
            try(InputStream in=new URL(urls[0]).openStream()){
                return android.graphics.BitmapFactory.decodeStream(in);
            } catch(IOException e){return null;}
        }
        @Override protected void onPostExecute(android.graphics.Bitmap bmp){ if(bmp!=null) iv.setImageBitmap(bmp); }
    }
}
