# Boiler Efficiency Pro v4.0 (Android)

Native Android engineering worksheet built with Kotlin + Jetpack Compose. This version integrates the Java IF97 library implementing the IAPWS-IF97 industrial formulation for water/steam properties. The included GitHub Actions workflow runs property regression tests before building a debug APK.

## v4.0 improvements
- **IAPWS-IF97 enthalpy calculation** from pressure and temperature for main steam and feedwater; replaces the previous hand-built saturation interpolation and constant-heat-capacity estimate.
- Engineering units are explicit in the property layer: pressure in bar absolute, temperature in °C, enthalpy in kJ/kg.
- Steam automatic mode accepts clearly superheated steam only. Near saturation, P/T does not define steam quality, so users must enter verified enthalpy/quality data manually.
- Feedwater automatic mode requires feedwater pressure (bar absolute) and temperature (°C), allowing compressed-liquid pressure effects to be included by the IF97 engine.
- Regression tests check an IAPWS-IF97 published Region 1 enthalpy reference point and saturation temperature, plus a valid superheated state.
- CI runs `testDebugUnitTest` before packaging the debug APK.
- Retains direct boiler efficiency, additional mass/energy streams, mass-balance warnings, indirect heat-loss sum, test identification, and shareable report.

## Property engine and reference tests
The app uses `com.hummeling:if97:2.0.0`, a Java implementation of IAPWS-IF97 published by Hummeling Engineering BV. Project page and API/unit documentation: https://www.if97.software/ . Source: https://github.com/hummeling/if97 . The library is licensed under LGPL-3.0-or-later; consult its license and comply with redistribution requirements for any product you distribute.

Reference regression point: the IAPWS-IF97 Region 1 example at T=300 K and p=3 MPa has h=115.331273 kJ/kg. In this library's ENGINEERING unit system this is T=26.85 °C and p=30 bar absolute. Test tolerance is 0.02 kJ/kg. The tests are regression checks, not a complete independent certification of every region or boundary case.

## Calculation equations

`Useful heat (kJ/h) = steam flow × steam enthalpy + blowdown flow × blowdown enthalpy − feedwater flow × feedwater enthalpy − spray flow × spray enthalpy`

`Fuel input (kJ/h) = fuel rate × calorific value`

`Direct efficiency (%) = useful heat / fuel input × 100`

`Indirect efficiency (%) = 100 − sum of entered heat-loss percentages`

All flow and energy units must be compatible, all significant boundary streams must be represented, and all values should come from calibrated instruments and synchronized readings. Select GCV/HHV or NCV/LHV consistently; the app does not convert between these bases.

## Important limitations
- IF97 is an industrial formulation for water/steam properties, but correct results still depend on valid input states, unit conversions, instrumentation and measurement uncertainty.
- P/T alone cannot determine quality for wet or saturated steam. Use verified quality/enthalpy information or manual steam-table values in that case.
- The indirect screen only sums loss percentages that the user has already calculated. It does not yet calculate flue-gas losses from O₂/CO₂, stack temperature, fuel analysis, moisture or ash data.
- This app is not a certified performance test instrument by itself. Validate the installed build and compare representative results against a trusted steam-table package and the applicable test standard before using it for contractual, acceptance or statutory purposes.
- This project has not been compiled or device-tested in the current environment. GitHub Actions must pass the property tests and build before installing the APK.

## Build on a computer
1. Open this folder in Android Studio.
2. Use JDK 17 and install Android SDK platform 35.
3. Sync Gradle, run `testDebugUnitTest`, then choose **Build > Build APK(s)**.
4. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Build using only an Android phone (GitHub Actions)
1. Download and extract this ZIP on your phone.
2. In your browser, create a private GitHub repository.
3. Upload all extracted files and folders to the repository root, including `.github/workflows/build-apk.yml`. If mobile upload hides `.github`, use GitHub Codespaces or an Android Git client.
4. Commit to `main`.
5. Open **Actions → Build Android APK**. Enable Actions if prompted, then run the workflow.
6. The workflow runs steam-property regression tests first and only builds the APK if they pass.
7. After a successful run, download artifact `Boiler-Efficiency-Pro-debug-apk`, extract it and install `app-debug.apk`.
8. Android may ask you to allow installation from that browser or file manager.
