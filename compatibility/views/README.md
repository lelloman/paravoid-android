# Views, generated bindings and fragment state probe

An ordinary Java app using AGP 8.13.2 ViewBinding/DataBinding, AppCompat 1.7.0,
Fragment 1.8.5 and Lifecycle 2.8.7. It has one AppCompatActivity, a fragment
inflated by XML, and a second fragment added to the back stack by class name.
There is no custom Application or instrumentation dependency in either APK.

```sh
ANDROID_HOME=/path/to/Android/Sdk bash compatibility/views/check.sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5584 \
  bash compatibility/views/check.sh --device
```

Device checks need Python 3.8+, adb on PATH and a dedicated unlocked emulator.
The driver installs and clears only the fixture apps, presses actual UI buttons,
and reads per-run observations from preferences. Preferences are not read by the
app to reconstruct its state. An unexpected foreground dialog fails the UI test
with a hierarchy dump rather than silently skipping actions.

## Scenarios

Twelve invariants are checked at five stages in each packaging mode (120
assertions): cold launch, editing/adding a fragment, Activity recreation, real
process death/restoration, and popping the restored fragment back stack.

The checks cover generated ViewBinding fields, XML FragmentContainerView
instantiation, AppCompat widget substitution, custom XML attributes, a custom
View's Parcelable saved state, generated DataBinding mapper lookup, two-way
EditText/LiveData binding, a custom BindingAdapter, saved-state-backed ViewModel
restoration, fragment back stack and Parcelable arguments, and payload loader
isolation. The default ViewModel factory creates the SavedStateHandle constructor.

The driver verifies that recreation keeps the PID but increments the saved
generation, and that process death changes the PID while restoring that generation
and the unique run token. The custom View's value is changed only by the initial
edit action, so later stages must restore its actual view state.

This fixture is not a guarantee for arbitrary fragments or generated bindings.
Nested fragments, FragmentFactory overrides, dialogs, RecyclerView adapters,
application-context inflation, Hilt fragment/View injection and older API levels
need separate coverage. Resources remain installed in the APK.

References: [ViewBinding](https://developer.android.com/topic/libraries/view-binding),
[two-way DataBinding](https://developer.android.com/topic/libraries/data-binding/two-way),
[fragment state](https://developer.android.com/guide/fragments/saving-state),
[SavedStateHandle](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).
