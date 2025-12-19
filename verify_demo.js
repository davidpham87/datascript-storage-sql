const puppeteer = require('puppeteer');
const http = require('http');
const serveHandler = require('serve-handler');

// Start a simple HTTP server to serve the public directory
const server = http.createServer((request, response) => {
  return serveHandler(request, response, {
    public: 'public',
    headers: [
      {
        source: '**/*.wasm',
        headers: [{
          key: 'Cross-Origin-Embedder-Policy',
          value: 'require-corp'
        }, {
          key: 'Cross-Origin-Opener-Policy',
          value: 'same-origin'
        }]
      },
      {
        source: '**/*.js',
        headers: [{
            key: 'Cross-Origin-Embedder-Policy',
            value: 'require-corp'
        }, {
            key: 'Cross-Origin-Opener-Policy',
            value: 'same-origin'
        }]
      },
      {
          source: '**/*.html',
          headers: [{
              key: 'Cross-Origin-Embedder-Policy',
              value: 'require-corp'
          }, {
              key: 'Cross-Origin-Opener-Policy',
              value: 'same-origin'
          }]
      }
    ]
  });
});

const PORT = 8080;

server.listen(PORT, async () => {
  console.log(`Server running at http://localhost:${PORT}`);

  let browser;
  try {
    browser = await puppeteer.launch({
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    const page = await browser.newPage();

    // Capture console logs
    page.on('console', msg => console.log('PAGE LOG:', msg.text()));
    page.on('response', response => {
      if (response.status() === 404) {
        console.log('404 Not Found:', response.url());
      }
    });
    page.on('requestfailed', request => {
      console.log('Request failed:', request.url(), request.failure().errorText);
    });

    // Go to the page
    await page.goto(`http://localhost:${PORT}`, { waitUntil: 'domcontentloaded' });

    // Wait for the success message in the logs div or console
    try {
        await page.waitForFunction(
            () => document.getElementById('logs').innerText.includes('SUCCESS: Data persisted and restored correctly.'),
            { timeout: 30000 }
        );
        console.log('TEST PASSED: Success message found.');
    } catch (e) {
        console.error('TEST FAILED: Success message not found within timeout.');
        // console.log('Current logs:', await page.$eval('#logs', el => el.innerText)); // optional
        process.exit(1);
    }

  } catch (error) {
    console.error('An error occurred:', error);
    process.exit(1);
  } finally {
    if (browser) await browser.close();
    server.close();
  }
});
