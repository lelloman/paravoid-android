import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = load('build-fixture')
device = load('device-check')


class GateTest(unittest.TestCase):
    def test_shell_assembly_uses_generated_table_and_keeps_code(self):
        with tempfile.TemporaryDirectory() as directory:
            original, resources, output = [Path(directory) / name for name in ('original.apk', 'resources.apk', 'out.apk')]
            with zipfile.ZipFile(original, 'w') as archive:
                for name, value in {'AndroidManifest.xml': b'manifest', 'resources.arsc': b'full',
                                    'res/layout/panel.xml': b'layout', 'assets/content.txt': b'movable',
                                    'assets/paravoid/module.zip': b'payload-code', 'classes.dex': b'shell-code',
                                    'META-INF/FIXTURE.RSA': b'old-signature'}.items():
                    archive.writestr(name, value)
            with zipfile.ZipFile(resources, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'manifest')
                archive.writestr('resources.arsc', b'pinned')
                archive.writestr('res/drawable/pinned.xml', b'pinned-file')
            build.strip_installed_resources(original, resources, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {'AndroidManifest.xml', 'resources.arsc',
                    'res/drawable/pinned.xml', 'classes.dex', 'assets/paravoid/module.zip'})
                self.assertEqual(archive.read('resources.arsc'), b'pinned')
                self.assertEqual(archive.read('classes.dex'), b'shell-code')
                self.assertEqual(archive.read('assets/paravoid/module.zip'), b'payload-code')
            with zipfile.ZipFile(resources, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'different-manifest')
            with self.assertRaises(AssertionError):
                build.strip_installed_resources(original, resources, output)

    def test_device_validator_rejects_stale_or_missing_evidence(self):
        result = dict(shell=True, title='Payload B', shellLabel='Automatic resource probe', pinnedTheme=True,
            providerTitle='Payload B', applicationTitle='Payload B', providerBeforeApplication=True,
            constructorTitle='Payload B', pid=100, titleId=0x7f010001, removed='absent', added='Added in B',
            worker=dict(pid=101, before=True, title='Payload B', early='Payload B', application='Payload B', constructor='Payload B'),
            view='Payload B', italian='Risorse B', library='Library resource', styleable='Library resource',
            libraryLoader=True, payloadTheme=True, asset='Asset B', dayAccent=0xff000099 - 2**32,
            nightAccent=0xff4444aa - 2**32, accent=0xff000099 - 2**32, night=False)
        device.validate('shell', 'B', result)
        for field, value in [('shell', False), ('title', 'Payload A'), ('providerTitle', 'Payload A'),
                             ('applicationTitle', 'Payload A'), ('constructorTitle', 'absent'),
                             ('providerBeforeApplication', False), ('pinnedTheme', False),
                             ('removed', 'Removed after A'), ('added', 'absent'), ('titleId', 0x01010001),
                             ('view', 'Payload A'), ('italian', 'Risorse A'), ('styleable', None),
                             ('libraryLoader', False), ('payloadTheme', False), ('asset', 'Asset A'),
                             ('dayAccent', 0), ('nightAccent', 0), ('accent', 0)]:
            with self.subTest(field=field), self.assertRaises(AssertionError):
                device.validate('shell', 'B', {**result, field: value})
        for field, value in [('pid', 100), ('before', False), ('title', 'Payload A'), ('early', 'absent'),
                             ('application', 'absent'), ('constructor', 'absent')]:
            mutated = copy.deepcopy(result)
            mutated['worker'][field] = value
            with self.subTest(worker=field), self.assertRaises(AssertionError):
                device.validate('shell', 'B', mutated)


if __name__ == '__main__':
    unittest.main()
