import { readFileSync, writeFileSync, unlinkSync, mkdirSync, rmdirSync, statSync } from 'ant:fs';

console.log('Testing ant:fs with synchronous operations...\n');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function testFs() {
  const testDir = 'tests/.fs_test_tmp_sync';
  const testFile = testDir + '/sync_test.txt';
  const testData = 'Hello from sync ant:fs!';

  try {
    // Test mkdirSync
    console.log('=== Test: mkdirSync ===');
    try {
      mkdirSync(testDir);
      console.log('✓ Directory created');
    } catch (e) {
      console.log('Directory might exist, continuing...');
    }

    // Test writeFileSync
    console.log('\n=== Test: writeFileSync ===');
    writeFileSync(testFile, testData);
    console.log('✓ File written');

    // Test readFileSync
    console.log('\n=== Test: readFileSync ===');
    const content = readFileSync(testFile, 'utf8');
    console.log('✓ File read, length:', content.length);
    assert(content === testData, 'Content mismatch');
    console.log('✓ Content matches!');

    // Test statSync
    console.log('\n=== Test: statSync ===');
    const stats = statSync(testFile);
    console.log('✓ File stats:');
    console.log('  Size:', stats.size);
    console.log('  Is file:', stats.isFile());
    console.log('  Is directory:', stats.isDirectory());
    assert(stats.size === testData.length, `Expected size ${testData.length}, got ${stats.size}`);
    assert(stats.isFile(), 'Expected file stat');
    assert(!stats.isDirectory(), 'Expected non-directory stat');

    // Cleanup
    console.log('\n=== Cleanup ===');
    unlinkSync(testFile);
    console.log('✓ File deleted');
    
    rmdirSync(testDir);
    console.log('✓ Directory deleted');

    console.log('\n✓✓✓ All tests passed! ✓✓✓');
  } catch (error) {
    console.error('\n✗ Test failed:', error);
    process.exit(1);
  }
}

// Run the synchronous function
testFs();
