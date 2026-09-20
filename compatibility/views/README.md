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

Verified on API 28 and API 36.1 x86_64/debug in both packaging modes. See the
[API 28 baseline](../API28.md) for the early saved-state decoding fix and scope.

Seventeen invariants are checked at five stages in each packaging mode (170
assertions): cold launch, editing/adding a fragment, Activity recreation, real
process death/restoration, and popping the restored fragment back stack.

The checks cover generated ViewBinding fields, XML FragmentContainerView
instantiation, AppCompat widget substitution, custom XML attributes, a custom
View's Parcelable saved state, generated DataBinding mapper lookup, two-way
EditText/LiveData binding, a custom BindingAdapter, saved-state-backed ViewModel
restoration, fragment back stack and Parcelable arguments, and payload loader
isolation. The default ViewModel factory creates the SavedStateHandle constructor.

Four additional checks inflate a custom View using Application, themed Application,
configured Application and configured Activity contexts. Configuration cases also
verify Italian resources, nested English configuration contexts, isolation of the
parent resources and stable inflater service identity.

The driver verifies that recreation keeps the PID but increments the saved
generation, and that process death changes the PID while restoring that generation
and the unique run token. The custom View's value is changed only by the initial
edit action, so later stages must restore its actual view state.
The ViewModel value is also captured before view hierarchy restoration can update
it through two-way binding: this prevents EditText's own saved state from masking
a broken SavedStateHandle. Additional identity checks require the same ViewModel
across Activity recreation and a new instance after process death.

## Failure found and fixed

The original twelve-check lifecycle matrix passed on API 36.1/debug without a
runtime change. Adding ordinary Application and themed Application inflation also
passed. Configuration-context inflation then failed in shell mode for both
Application and Activity contexts at every stage (10 failures); the normal control
passed. The nested locale assertions were added after reproducing this failure.

Android's derived ContextImpl retained the installed shell loader. LayoutInflater
uses its context's classloader, not the current thread context loader, so it could
not find the payload View. The shell now wraps configuration contexts to preserve
their resources while supplying the payload loader and a cached inflater cloned
into that wrapper. Repeated configuration derivation keeps this behavior. The
plugin supplies an Activity override only when the payload hierarchy has none;
explicit overrides, including inherited final methods, are preserved.

The final matrix passes all 170 assertions on API 36.1/debug. This is a narrow
configuration-context fix using public APIs, not replacement of the installed
package's classloader or a blanket rewrite of Context operations.

This fixture is not a guarantee for arbitrary fragments or generated bindings.
Nested fragments, FragmentFactory overrides, dialogs, RecyclerView adapters,
Hilt fragment/View injection and older API levels need separate coverage.
Explicit Activity configuration-context overrides remain responsible for their
own loader behavior. Raw base contexts, display/window/device-protected contexts
and contexts created by other components need separate tests; they are not
covered by this fix. Resources remain installed in the APK.

References: [ViewBinding](https://developer.android.com/topic/libraries/view-binding),
[two-way DataBinding](https://developer.android.com/topic/libraries/data-binding/two-way),
[fragment state](https://developer.android.com/guide/fragments/saving-state),
[SavedStateHandle](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).
