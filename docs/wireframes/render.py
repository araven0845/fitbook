import pathlib
from playwright.sync_api import sync_playwright
from PIL import Image

HERE = pathlib.Path(__file__).parent
pages = ["1-home.html", "2-slots.html", "3-book.html", "4-confirmation.html"]
pngs = []

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path="/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
                                 args=["--no-sandbox"])
    page = browser.new_page(viewport={"width": 960, "height": 700})
    for name in pages:
        page.goto(f"file://{HERE / name}")
        page.wait_for_timeout(100)
        out = HERE / (name.replace(".html", ".png"))
        page.screenshot(path=str(out), full_page=True)
        pngs.append(out)
    browser.close()

images = [Image.open(p).convert("RGB") for p in pngs]
images[0].save(HERE / "wireframes.pdf", save_all=True, append_images=images[1:])
print("done:", [str(p) for p in pngs])
