"""Select only the fixture's exact, package-manager-resolved controls alias."""


def resolved_updates_alias(query_output, application_id):
    component = application_id + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher'
    return component if component in {line.strip() for line in query_output.splitlines()} else None
