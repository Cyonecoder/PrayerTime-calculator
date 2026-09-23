import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import containerize as c

class DetectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def app(self, relative, plugin, version=21):
        directory = self.root / relative
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'settings.gradle').write_text('rootProject.name="test"')
        (directory / 'build.gradle').write_text(f"plugins {{ id '{plugin}' }}\njava {{ toolchain {{ languageVersion = JavaLanguageVersion.of({version}) }} }}")
        (directory / 'gradlew').write_text('#!/bin/sh\n')
        return directory

    def test_discovers_root_and_nested_boot_without_hardcoded_paths(self):
        self.app('.', 'application')
        self.app('different/service', 'org.springframework.boot')
        self.app('build/ignored', 'application')
        self.app('library', 'java-library')
        apps = c.discover(self.root)
        self.assertEqual({a['path']: a['task'] for a in apps}, {'.': 'installDist', 'different/service': 'bootJar'})

    def test_rejects_mixed_runtime_versions(self):
        self.app('.', 'application')
        self.app('api', 'org.springframework.boot', 17)
        with self.assertRaisesRegex(ValueError, 'different Java'):
            c.discover(self.root)

    def test_assembles_both_built_outputs_in_one_context(self):
        root = self.app('.', 'application')
        api = self.app('api', 'org.springframework.boot')
        (root / 'build/install/example/bin').mkdir(parents=True)
        (root / 'build/install/example/bin/example').write_text('#!/bin/bash\nexec java Main "$@"')
        (api / 'build/libs').mkdir(parents=True)
        (api / 'build/libs/service.jar').write_bytes(b'jar')
        output = self.root / '.container-build'
        with patch.object(c, 'ROOT', self.root), patch.object(c, 'OUTPUT', output), patch.object(c.subprocess, 'run') as run:
            c.assemble(c.discover(self.root), {'arguments': {'.': ['serve']}, 'ports': [8080, 8081]})
        self.assertEqual(run.call_count, 2)
        self.assertTrue(all('test' in call.args[0] for call in run.call_args_list))
        manifest = json.loads((output / 'manifest.json').read_text())
        self.assertEqual(len(manifest), 2)
        self.assertIn('EXPOSE 8080 8081', (output / 'Dockerfile').read_text())
        launcher = (output / 'start.sh').read_text()
        self.assertIn('wait -n', launcher)
        self.assertIn('java -jar', launcher)
        self.assertIn(' serve &', launcher)
        self.assertIn('stop_all', launcher)
        self.assertEqual(len(list((output / 'apps').iterdir())), 2)

if __name__ == '__main__':
    unittest.main()
