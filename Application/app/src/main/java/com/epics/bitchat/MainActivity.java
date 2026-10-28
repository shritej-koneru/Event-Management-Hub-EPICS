package com.epics.bitchat;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.epics.bitchat.mesh.BleAdvertiserTransport;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshNode;
import com.epics.bitchat.service.MeshTransportService;
import com.epics.bitchat.ui.ConsoleFragment;
import com.epics.bitchat.ui.NavigationFragment;
import com.epics.bitchat.ui.PeersFragment;
import com.epics.bitchat.ui.SendFragment;
import com.epics.bitchat.ui.SettingsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private Fragment consoleFragment;
    private Fragment peersFragment;
    private Fragment sendFragment;
    private Fragment navigationFragment;
    private Fragment settingsFragment;

    private boolean wasBlePermitted = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    granted -> {
                        // The mesh was started before the dialog was shown; once the
                        // Bluetooth permissions arrive, restart so the real BLE transport
                        // can scan/advertise.
                        MeshManager manager = MeshManager.get(this);
                        if (!wasBlePermitted && manager.hasBlePermissions()) {
                            manager.restart();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_console) {
                showFragment(consoleFragment());
                return true;
            } else if (id == R.id.nav_peers) {
                showFragment(peersFragment());
                return true;
            } else if (id == R.id.nav_send) {
                showFragment(sendFragment());
                return true;
            } else if (id == R.id.nav_navigation) {
                showFragment(navigationFragment());
                return true;
            } else if (id == R.id.nav_settings) {
                showFragment(settingsFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_console);
        } else {
            String tag = savedInstanceState.getString("fragment_tag", "console");
            restoreFragment(tag);
        }

        MeshTransportService.start(this);
        requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permissions may have been granted in Settings while the app was in the
        // background; if the BLE transport couldn't start earlier, give it a retry.
        MeshManager manager = MeshManager.get(this);
        if (MeshManager.MODE_BLE.equals(manager.getMode()) && manager.hasBlePermissions()) {
            MeshNode node = manager.getMeshNode();
            if (node != null && node.getTransport() instanceof BleAdvertiserTransport
                    && !((BleAdvertiserTransport) node.getTransport()).isAvailable()) {
                manager.restart();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        FragmentManager fm = getSupportFragmentManager();
        Fragment current = null;
        for (Fragment f : fm.getFragments()) {
            if (f.isVisible()) {
                current = f;
                break;
            }
        }
        if (current != null) {
            outState.putString("fragment_tag", current.getTag());
        }
    }

    private void restoreFragment(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) {
            showFragment(existing);
        } else {
            switch (tag) {
                case "peers":
                    showFragment(peersFragment());
                    break;
                case "send":
                    showFragment(sendFragment());
                    break;
                case "navigation":
                    showFragment(navigationFragment());
                    break;
                case "settings":
                    showFragment(settingsFragment());
                    break;
                default:
                    showFragment(consoleFragment());
                    break;
            }
        }
    }

    private Fragment consoleFragment() {
        if (consoleFragment == null) {
            consoleFragment = new ConsoleFragment();
        }
        return consoleFragment;
    }

    private Fragment peersFragment() {
        if (peersFragment == null) {
            peersFragment = new PeersFragment();
        }
        return peersFragment;
    }

    private Fragment sendFragment() {
        if (sendFragment == null) {
            sendFragment = new SendFragment();
        }
        return sendFragment;
    }

    private Fragment navigationFragment() {
        if (navigationFragment == null) {
            navigationFragment = new NavigationFragment();
        }
        return navigationFragment;
    }

    private Fragment settingsFragment() {
        if (settingsFragment == null) {
            settingsFragment = new SettingsFragment();
        }
        return settingsFragment;
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment, fragment.getTag() == null
                        ? tagOf(fragment) : fragment.getTag())
                .commit();
    }

    private String tagOf(Fragment fragment) {
        if (fragment instanceof ConsoleFragment) return "console";
        if (fragment instanceof PeersFragment) return "peers";
        if (fragment instanceof SendFragment) return "send";
        if (fragment instanceof NavigationFragment) return "navigation";
        return "settings";
    }

    private void requestRuntimePermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        wasBlePermitted = MeshManager.get(this).hasBlePermissions();
        String[] array = needed.toArray(new String[0]);
        if (array.length > 0) {
            permissionLauncher.launch(Arrays.asList(array).toArray(new String[0]));
        }
    }
}
