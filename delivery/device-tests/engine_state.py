"""Read the production update owner's non-security scheduling record."""
from controls_access import resolved_updates_alias

OPERATIONS = 'no_backup/paravoid-updates-v1/operations.properties'
OWNER_SUFFIX = ':paravoid_updates'


def properties(text):
    # Fields asserted by device tests contain only plain ASCII scalar values.
    return dict(line.split('=', 1) for line in text.splitlines()
                if line and not line.startswith(('#', '!')) and '=' in line)


def state(adb, app):
    return properties(adb('shell', 'run-as', app, 'cat', OPERATIONS))


def controls(adb, app):
    component = app + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'
    resolved = adb('shell', 'cmd', 'package', 'query-activities', '--brief',
                   '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', app)
    assert resolved_updates_alias(resolved, app) == component, 'Build with controlsLauncher=true'
    # Force a fresh controls Activity after owner death; task reuse can retain
    # an old UI snapshot while the Binder connection is being re-established.
    adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', component)
