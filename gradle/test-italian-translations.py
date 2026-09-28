"""Exercise the translation gate in an isolated Gradle project, without Android."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent

with tempfile.TemporaryDirectory(prefix="paravoid-translations-") as directory:
    project = Path(directory)
    (project / "settings.gradle").write_text("rootProject.name = 'translation-test'\n")
    (project / "build.gradle").write_text(
        "tasks.register('preBuild')\n"
        "tasks.register('check')\n"
        f"apply from: '{ROOT / 'gradle/italian-translations.gradle'}'\n"
    )
    default = project / "src/main/res/values"
    italian = project / "src/main/res/values-it"
    default.mkdir(parents=True)
    italian.mkdir(parents=True)
    (default / "text.xml").write_text('''<resources>
        <string name="greeting">Hello</string>
        <string name="brand" translatable="false">Brand</string>
        <plurals name="count"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
        <string-array name="planets"><item>Earth</item><item>Mars</item></string-array>
    </resources>''')
    complete = '''<resources>
        <string name="greeting">Ciao</string>
        <plurals name="count"><item quantity="one">Uno</item><item quantity="other">Molti</item></plurals>
        <string-array name="planets"><item>Terra</item><item>Marte</item></string-array>
    </resources>'''

    def verify(label, contents, task, expected=None):
        (italian / "text.xml").write_text(contents)
        result = subprocess.run(
            [str(ROOT / "gradlew"), "-p", str(project), task, "--offline", "--console=plain"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        if expected is None:
            assert result.returncode == 0, result.stdout
        else:
            assert result.returncode != 0 and expected in result.stdout, result.stdout
        print(f"PASS: {label}", flush=True)

    verify("complete translations and nontranslatable exemption", complete, "preBuild")
    verify("preBuild rejects missing string", complete.replace('<string name="greeting">Ciao</string>', ''),
           "preBuild", "string/greeting")
    verify("check rejects missing plural form", complete.replace('<item quantity="one">Uno</item>', ''),
           "check", "plurals/count[one]")
    verify("rejects incomplete array", complete.replace('<item>Marte</item>', ''),
           "preBuild", "string-array/planets (item count differs)")
    (italian / "text.xml").unlink()
    italian.rmdir()
    result = subprocess.run(
        [str(ROOT / "gradlew"), "-p", str(project), "preBuild", "--offline", "--console=plain"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    assert result.returncode != 0 and "string/greeting" in result.stdout, result.stdout
    print("PASS: rejects absent Italian resource directory", flush=True)
