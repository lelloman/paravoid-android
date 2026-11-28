package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translations of shell-owned UI texts, keyed by their English text.
 *
 * <p>Shell screens run in the payload-free recovery process and must keep working when the
 * payload or its resources are broken, so they do not use Android resources (which would also
 * have to be pinned in every shell). A text without a translation for the device language is
 * shown in English. Format texts use {@link String#format} placeholders such as {@code %1$s}.
 */
public final class ShellText {
    private ShellText() {}

    /** The text in the context's current language, formatted with {@code args} when given. */
    public static String tr(Context context, String english, Object... args) {
        return tr(context.getResources().getConfiguration().getLocales().get(0), english, args);
    }

    static String tr(Locale locale, String english, Object... args) {
        Map<String, String> table = locale == null ? null : TABLES.get(locale.getLanguage());
        String text = table == null ? null : table.get(english);
        if (text == null) text = english;
        if (args.length == 0) return text;
        try { return String.format(locale, text, args); }
        catch (java.util.IllegalFormatException invalid) { return String.format(Locale.ROOT, english, args); }
    }

    static final Map<String, Map<String, String>> TABLES = new HashMap<>();
    static {
        Map<String, String> it = new HashMap<>();
        TABLES.put("it", it);
        // Crash recovery screen.
        it.put("App recovery", "Ripristino dell'app");
        it.put("App updates", "Aggiornamenti dell'app");
        it.put("The app could not continue. You can check for an update or try opening it again.",
            "L'app non è riuscita a continuare. Puoi cercare un aggiornamento o provare a riaprirla.");
        it.put("Check for updates and restart to apply them.", "Cerca aggiornamenti e riavvia per applicarli.");
        it.put("Recovery could not initialize.", "Impossibile inizializzare il ripristino.");
        it.put("No crash report recorded.", "Nessun rapporto di arresto anomalo registrato.");
        it.put("Crash details could not be read.", "Impossibile leggere i dettagli dell'arresto anomalo.");
        it.put("Crash details", "Dettagli dell'arresto anomalo");
        it.put("Copy", "Copia");
        it.put("Close", "Chiudi");
        it.put("Check again", "Controlla di nuovo");
        it.put("Update", "Aggiorna");
        it.put("Cancel", "Annulla");
        it.put("Continue", "Continua");
        it.put("Restart", "Riavvia");
        it.put("Retry app", "Riprova l'app");
        it.put("Update service could not initialize. Install a repaired shell to recover.",
            "Impossibile inizializzare il servizio di aggiornamento. Installa una versione corretta dell'app per ripristinarla.");
        it.put("Restart app?", "Riavviare l'app?");
        it.put("This stops all app processes. Unsaved work may be lost. Without a repaired update, the app may crash again.",
            "Tutti i processi dell'app verranno interrotti. Il lavoro non salvato potrebbe andare perso. Senza un aggiornamento correttivo, l'app potrebbe arrestarsi di nuovo.");
        it.put("Could not restart. Please try again.", "Impossibile riavviare. Riprova.");
        it.put("Working…", "Operazione in corso…");
        it.put("The action could not be completed. Please try again.", "Impossibile completare l'azione. Riprova.");
        it.put("No app on this device can open this.", "Nessuna app su questo dispositivo può aprire questo contenuto.");
        // Recovery update status.
        it.put("Ready to check for updates.", "Pronto per cercare aggiornamenti.");
        it.put("Update ready. Restart to apply it.", "Aggiornamento pronto. Riavvia per applicarlo.");
        it.put("Verifying and staging update…", "Verifica e preparazione dell'aggiornamento…");
        it.put("Downloading update: %1$s / %2$s bytes", "Download dell'aggiornamento: %1$s / %2$s byte");
        it.put("Update unavailable (%1$s).", "Aggiornamento non disponibile (%1$s).");
        it.put("Update available: %1$s", "Aggiornamento disponibile: %1$s");
        it.put("Checking for updates…", "Ricerca di aggiornamenti…");
        it.put("No compatible update is available.", "Nessun aggiornamento compatibile disponibile.");
        // Notifications, shortcut and launcher.
        it.put("Update available", "Aggiornamento disponibile");
        it.put("Install version %1$s?", "Installare la versione %1$s?");
        it.put("Install version %1$s? It will be used the next time the app starts.",
            "Installare la versione %1$s? Verrà usata al prossimo avvio dell'app.");
        it.put("Install", "Installa");
        it.put("Not now", "Non ora");
        it.put("App unavailable", "App non disponibile");
        it.put("Open the app to recover.", "Apri l'app per ripristinarla.");
        it.put("Paravoid app updates", "Aggiornamenti dell'app Paravoid");
        it.put("Unable to initialize application: %1$s", "Impossibile inizializzare l'applicazione: %1$s");
        // Restart screen fallbacks (the generated theme strings are translated as resources).
        it.put("Restart to update?", "Riavviare per aggiornare?");
        it.put("%1$s will close and reopen. Ongoing work stops and unsaved changes may be lost. A ready update is applied when it reopens.",
            "L'app %1$s verrà chiusa e riaperta. Le attività in corso si interrompono e le modifiche non salvate potrebbero andare perse. Un aggiornamento pronto viene applicato alla riapertura.");
        it.put("Restarting %1$s", "Riavvio di %1$s");
        it.put("This only takes a moment.", "Ci vorrà solo un momento.");
        it.put("Restart unavailable", "Riavvio non disponibile");
        it.put("%1$s could not restart. You can try again from the app’s update controls.",
            "Impossibile riavviare %1$s. Puoi riprovare dai controlli di aggiornamento dell'app.");
        // Update controls screen.
        it.put("‹ Back", "‹ Indietro");
        it.put("Back", "Indietro");
        it.put("Manage downloads and choose when to apply an update.", "Gestisci i download e scegli quando applicare un aggiornamento.");
        it.put("Loading update status…", "Caricamento dello stato degli aggiornamenti…");
        it.put("Connecting to update controls.", "Connessione ai controlli di aggiornamento.");
        it.put("Check for updates", "Cerca aggiornamenti");
        it.put("Cancel download", "Annulla download");
        it.put("Cancel check", "Annulla ricerca");
        it.put("Cancel retry", "Annulla nuovo tentativo");
        it.put("Updates unavailable", "Aggiornamenti non disponibili");
        it.put("Close and reopen the app to try again. If this continues, get a replacement app installer from your distributor.",
            "Chiudi e riapri l'app per riprovare. Se il problema persiste, richiedi al distributore un nuovo programma di installazione dell'app.");
        it.put("Automatic updates", "Aggiornamenti automatici");
        it.put("These preferences apply to background updates. You can always check or download manually.",
            "Queste preferenze si applicano agli aggiornamenti in background. Puoi sempre cercare o scaricare manualmente.");
        it.put("Check for updates automatically", "Cerca aggiornamenti automaticamente");
        it.put("Allow automatic downloads", "Consenti download automatici");
        it.put("Download only on unmetered networks", "Scarica solo su reti non a consumo");
        it.put("Unmetered networks usually include Wi-Fi.", "Le reti non a consumo di solito includono il Wi-Fi.");
        it.put("Keep update connection in background", "Mantieni la connessione per gli aggiornamenti in background");
        it.put("Could not change background update connection", "Impossibile modificare la connessione in background per gli aggiornamenti");
        it.put("Keep listening for updates when the app is not open. Shows an ongoing notification. Off by default.",
            "Continua a ricevere gli aggiornamenti quando l'app non è aperta. Mostra una notifica persistente. Disattivato per impostazione predefinita.");
        it.put("Auto restart app", "Riavvia automaticamente l'app");
        it.put("Could not save auto restart setting", "Impossibile salvare l'impostazione di riavvio automatico");
        it.put("When enabled, a downloaded update restarts the app while it is open. Ongoing work may stop and unsaved changes may be lost.",
            "Se attivo, un aggiornamento scaricato riavvia l'app mentre è aperta. Le attività in corso potrebbero interrompersi e le modifiche non salvate andare perse.");
        it.put("Show advanced details", "Mostra dettagli avanzati");
        it.put("Hide advanced details", "Nascondi dettagli avanzati");
        it.put("Saved previous versions", "Versioni precedenti salvate");
        it.put("Number of previous versions to keep", "Numero di versioni precedenti da conservare");
        it.put("Keep none", "Non conservare");
        it.put("Keep 1 version", "Conserva 1 versione");
        it.put("Keep 2 versions", "Conserva 2 versioni");
        it.put("Keep 3 versions", "Conserva 3 versioni");
        it.put("Restart app…", "Riavvia l'app…");
        it.put("Retry failed app version…", "Riprova la versione dell'app non riuscita…");
        it.put("Checking for updates", "Ricerca di aggiornamenti");
        it.put("Looking for a compatible version of this app.", "Ricerca di una versione compatibile di questa app.");
        it.put("Checking…", "Ricerca…");
        it.put("Downloading update", "Download dell'aggiornamento");
        it.put("You can keep using the app while the download finishes.", "Puoi continuare a usare l'app mentre il download viene completato.");
        it.put("Downloading…", "Download…");
        it.put("Preparing update", "Preparazione dell'aggiornamento");
        it.put("Verifying the download and getting it ready to use.", "Verifica del download e preparazione all'uso.");
        it.put("Preparing…", "Preparazione…");
        it.put("Ready to restart", "Pronto per il riavvio");
        it.put("Version %1$s is downloaded. Restart the app to start using it.",
            "La versione %1$s è stata scaricata. Riavvia l'app per iniziare a usarla.");
        it.put("Restart to apply update", "Riavvia per applicare l'aggiornamento");
        it.put("Version %1$s is ready. It will be applied the next time the app starts.",
            "La versione %1$s è pronta. Verrà applicata al prossimo avvio dell'app.");
        it.put("Waiting to retry", "In attesa di riprovare");
        it.put("The last attempt could not finish. The app will try again when the scheduled retry can run.",
            "L'ultimo tentativo non è stato completato. L'app riproverà quando potrà eseguire il tentativo pianificato.");
        it.put("Retry now", "Riprova ora");
        it.put("Update couldn’t finish", "Impossibile completare l'aggiornamento");
        it.put("Try again", "Riprova");
        it.put("Version %1$s is available to download.", "La versione %1$s è disponibile per il download.");
        it.put("Download update", "Scarica aggiornamento");
        it.put("Update cancelled", "Aggiornamento annullato");
        it.put("Check again whenever you’re ready to continue.", "Controlla di nuovo quando vuoi continuare.");
        it.put("Download the app to begin", "Scarica l'app per iniziare");
        it.put("Check for an available version to get started.", "Cerca una versione disponibile per iniziare.");
        it.put("An app repair is needed", "È necessario riparare l'app");
        it.put("Check for a newer version. If none is available, contact your distributor for a replacement installer.",
            "Cerca una versione più recente. Se non è disponibile, contatta il distributore per un nuovo programma di installazione.");
        it.put("No update available", "Nessun aggiornamento disponibile");
        it.put("Ready to check", "Pronto per la ricerca");
        it.put("Check for the latest compatible version of this app.", "Cerca l'ultima versione compatibile di questa app.");
        it.put("Restarting app…", "Riavvio dell'app…");
        it.put("Stopping app work and opening the app again.", "Interruzione delle attività dell'app e riapertura.");
        it.put("Installed version   %1$s", "Versione installata   %1$s");
        it.put("Ready to install      %1$s", "Pronta da installare   %1$s");
        it.put("Unavailable", "Non disponibile");
        it.put("Not installed", "Non installata");
        it.put("Not checked yet", "Non ancora controllato");
        it.put("Last checked %1$s", "Ultimo controllo: %1$s");
        it.put("Next attempt %1$s", "Prossimo tentativo: %1$s");
        it.put("%1$s downloaded", "%1$s scaricati");
        it.put("%1$s of %2$s · %3$d%%", "%1$s di %2$s · %3$d%%");
        it.put("Installed release: %1$s", "Release installata: %1$s");
        it.put("Available release: %1$s", "Release disponibile: %1$s");
        it.put("Pending release: %1$s", "Release in attesa: %1$s");
        it.put("App state unavailable", "Stato dell'app non disponibile");
        it.put("App state: %1$s", "Stato dell'app: %1$s");
        it.put("Update storage: %1$s", "Spazio per gli aggiornamenti: %1$s");
        it.put("App state error: %1$s", "Errore dello stato dell'app: %1$s");
        it.put("Update error: %1$s", "Errore di aggiornamento: %1$s");
        it.put("None", "Nessuna");
        it.put("%1$s (version %2$s)", "%1$s (versione %2$s)");
        it.put("Update access is unavailable. Try again, or get a newly authorized installer from your distributor. Your installed app remains available offline.",
            "L'accesso agli aggiornamenti non è disponibile. Riprova o richiedi al distributore un nuovo programma di installazione autorizzato. L'app installata resta disponibile offline.");
        it.put("Check the device’s date and time, then try again.", "Controlla la data e l'ora del dispositivo, poi riprova.");
        it.put("Free up some space on this device, then try again.", "Libera spazio su questo dispositivo, poi riprova.");
        it.put("The available version changed. Check again before downloading.",
            "La versione disponibile è cambiata. Controlla di nuovo prima di scaricare.");
        it.put("Get a new app installer from your distributor to continue receiving updates.",
            "Richiedi al distributore un nuovo programma di installazione dell'app per continuare a ricevere aggiornamenti.");
        it.put("No compatible version is currently available for this app.",
            "Al momento non è disponibile nessuna versione compatibile di questa app.");
        it.put("The update could not be completed. Try again, or open advanced details if the problem continues.",
            "Impossibile completare l'aggiornamento. Riprova o apri i dettagli avanzati se il problema persiste.");
        it.put("Restart app now?", "Riavviare l'app ora?");
        it.put("This stops ongoing app work, including playback and jobs. Unsaved changes may be lost. A downloaded update will be applied when the app opens again.",
            "Le attività in corso dell'app, incluse riproduzioni e processi, verranno interrotte. Le modifiche non salvate potrebbero andare perse. Un aggiornamento scaricato verrà applicato alla riapertura dell'app.");
        it.put("Couldn’t restart the app", "Impossibile riavviare l'app");
        it.put("Stop any ongoing app work and try again. You can also close the app from Android settings and reopen it.",
            "Interrompi le attività in corso dell'app e riprova. Puoi anche chiudere l'app dalle impostazioni di Android e riaprirla.");
        it.put("OK", "OK");
        it.put("Retry failed app version?", "Riprovare la versione dell'app non riuscita?");
        it.put("Retry version %1$s the next time the app starts? It previously failed to start and may fail again. This does not restore older app data.",
            "Riprovare la versione %1$s al prossimo avvio dell'app? In precedenza non è riuscita ad avviarsi e potrebbe non riuscirci di nuovo. I dati precedenti dell'app non vengono ripristinati.");
        it.put("Retry version", "Riprova versione");
        // Previous versions: explicit relaunch and the rollback hold.
        it.put("Previous versions",
            "Versioni precedenti");
        it.put("Versions kept on this device. If the current version has a problem, you can go back to one of them.",
            "Versioni conservate su questo dispositivo. Se la versione attuale ha un problema, puoi tornare a una di esse.");
        it.put("Resume automatic updates…",
            "Riprendi gli aggiornamenti automatici…");
        it.put("Restart to apply the downloaded update before going back to another version.",
            "Riavvia per applicare l'aggiornamento scaricato prima di tornare a un'altra versione.");
        it.put("Keep previous versions on this device, so you can go back to one. Going back does not restore older app data.",
            "Conserva le versioni precedenti su questo dispositivo per poter tornare a una di esse. Tornare indietro non ripristina i dati precedenti dell'app.");
        it.put("Updates held up to: %1$s",
            "Aggiornamenti sospesi fino a: %1$s");
        it.put("Automatic updates are paused up to version %1$s because you went back to an older version. Versions newer than %1$s still install.",
            "Gli aggiornamenti automatici sono sospesi fino alla versione %1$s perché sei tornato a una versione precedente. Le versioni successive alla %1$s vengono comunque installate.");
        it.put("Version %1$s",
            "Versione %1$s");
        it.put("Release %1$s · %2$s",
            "Release %1$s · %2$s");
        it.put("Last ran successfully %1$s",
            "Ultimo avvio riuscito: %1$s");
        it.put("Ran successfully before (time not recorded)",
            "Avviata correttamente in passato (data non registrata)");
        it.put("The newer version you went back from.",
            "La versione più recente da cui sei tornato indietro.");
        it.put("Can’t relaunch: it was built for a different installed app version (shell).",
            "Impossibile riavviarla: è stata creata per un'altra versione dell'app installata (shell).");
        it.put("Can’t relaunch: it was built for a different installed app version (shell %1$s).",
            "Impossibile riavviarla: è stata creata per un'altra versione dell'app installata (shell %1$s).");
        it.put("Can’t relaunch: the installed app no longer accepts a version this old.",
            "Impossibile riavviarla: l'app installata non accetta più una versione così vecchia.");
        it.put("Return to this version…",
            "Torna a questa versione…");
        it.put("Relaunch this version…",
            "Riavvia questa versione…");
        it.put("It will be used the next time the app starts.",
            "Verrà usata al prossimo avvio dell'app.");
        it.put("The app restarts now; ongoing work stops and unsaved changes may be lost.",
            "L'app si riavvia ora: le attività in corso si interrompono e le modifiche non salvate potrebbero andare perse.");
        it.put("Version %1$s is older than the one you are using. Data saved by a newer version may not work with it: the app may lose settings, behave unexpectedly or fail to start. If it fails to start, you can come back to the newer version from the recovery screen. Automatic updates will not install version %2$s or older until you resume them.",
            "La versione %1$s è precedente a quella che stai usando. I dati salvati da una versione più recente potrebbero non funzionare con essa: l'app potrebbe perdere impostazioni, comportarsi in modo imprevisto o non avviarsi. Se non si avvia, puoi tornare alla versione più recente dalla schermata di ripristino. Gli aggiornamenti automatici non installeranno la versione %2$s o precedenti finché non li riprendi.");
        it.put("Run version %1$s again. Data saved by the version you are using now may not work with it.",
            "Esegui di nuovo la versione %1$s. I dati salvati dalla versione che stai usando ora potrebbero non funzionare con essa.");
        it.put("Go back to version %1$s?",
            "Tornare alla versione %1$s?");
        it.put("Return to version %1$s?",
            "Tornare di nuovo alla versione %1$s?");
        it.put("Relaunch",
            "Riavvia con questa versione");
        it.put("Preparing version %1$s…",
            "Preparazione della versione %1$s…");
        it.put("Couldn’t switch version",
            "Impossibile cambiare versione");
        it.put("The stored files of this version failed verification, so it can’t be used.",
            "I file salvati di questa versione non hanno superato la verifica, quindi non può essere usata.");
        it.put("This version doesn’t fit the installed app, so it can’t be used.",
            "Questa versione non è compatibile con l'app installata, quindi non può essere usata.");
        it.put("The installed app no longer accepts a version this old.",
            "L'app installata non accetta più una versione così vecchia.");
        it.put("This version can’t be selected right now. Restart the app or wait for other app work to finish, then try again.",
            "Al momento non è possibile selezionare questa versione. Riavvia l'app o attendi che finiscano le altre attività dell'app, poi riprova.");
        it.put("The version could not be selected (%1$s).",
            "Impossibile selezionare la versione (%1$s).");
        it.put("Resume automatic updates?",
            "Riprendere gli aggiornamenti automatici?");
        it.put("Updates up to version %1$s, including the version you went back from, can be installed automatically again.",
            "Gli aggiornamenti fino alla versione %1$s, inclusa quella da cui sei tornato indietro, potranno di nuovo essere installati automaticamente.");
        it.put("Resume",
            "Riprendi");
        it.put("Previous versions…",
            "Versioni precedenti…");
    }
}
