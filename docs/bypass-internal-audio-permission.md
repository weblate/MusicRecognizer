# Bypass permission requests for internal audio capture

Audile uses the Android Media Projection API (screen casting) to capture device audio. This is the only official way to capture internal audio on Android. The Media Projection API is designed in such a way that we cannot request access to audio exclusively, which is why the system prompts for permission to record the entire screen. However, Audile does not record or process any visual data from your screen, as only internal audio is captured for music recognition purposes.

By default, Android requires you to approve a permission prompt every time a new internal audio recording session starts. If you find this repetitive prompt annoying, you can grant Audile permanent access to capture internal audio.

*Note: You will still see the standard Android screencasting indicator in your status bar while the recording is active. Also, keep in mind that some apps restrict their own audio from being captured and the actions below do not allow you to bypass this.*

Starting with Android 15, the screen casting permission dialog can no longer be shown while the device is locked. If you start recognition that uses screen casting from the lock screen, for example by tapping the quick settings tile, the permission dialog, and therefore the recognition itself, will only run after you unlock the device. Granting Audile permanent access to screen casting (using one of the methods below) bypasses this restriction and lets you start device audio recognition even from the lock screen.

Below are several methods to grant this permission permanently.

<details open>
<summary><b>Method 1: Using ADB via a PC</b></summary>

1. Ensure you have **ADB** (Android Debug Bridge) installed on your computer. You can verify this by running `adb version` in your terminal. If it is not installed, follow the standard installation steps for your OS.
2. On your phone, navigate to **Developer options** and temporarily enable **USB debugging**.
3. Connect your phone to your computer, open a terminal, and execute the following command:
   ```bash
   adb shell appops set com.mrsep.musicrecognizer PROJECT_MEDIA allow
   ```
   This grants Audile automatic permission to record internal audio.
   
   If you want to revert this change later, execute:
   ```bash
   adb shell appops set com.mrsep.musicrecognizer PROJECT_MEDIA default
   ```
4. Disable **USB debugging** and **Developer options** if you no longer need them.

</details>

<details>
<summary><b>Method 2: Using Shizuku & App Ops</b></summary>

1. Ensure you have [Shizuku](https://github.com/RikkaApps/Shizuku) (or a fork) installed and running on your device.
2. Install and launch an AppOps management tool, such as [App Ops](https://appops.rikka.app/download/).
3. Locate **Audile** in the application list and change the **Project media** permission state to **Allow**. This grants the permanent permission.
   
   If you want to revert this change later, set **Project media** back to **Allow only while using the app** (Default).
4. You can safely uninstall App Ops and stop Shizuku once the permission is granted, as they are not required to keep the permission active.

</details>

<details>
<summary><b>Alternatives</b></summary>

If you do not have access to a PC, you can execute the necessary command directly on your device using one of the following approaches:

*   **Wireless Debugging:** If you have a second Android device, you can connect them via Wireless Debugging and run the ADB command from the second device.
*   **Local ADB:** Use an app like [LADB](https://github.com/tytydraco/ladb) to establish a local ADB shell directly on your phone, then run the command provided in Method 1.
*   **Root Access:** If your device is rooted, simply open a terminal emulator (such as [Termux](https://github.com/termux/termux-app)), request root access by typing `su`, and then execute:
    ```bash
    appops set com.mrsep.musicrecognizer PROJECT_MEDIA allow
    ```

</details>