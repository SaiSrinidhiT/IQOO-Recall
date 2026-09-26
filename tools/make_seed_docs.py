"""Generates SYNTHETIC seed documents for testing (brief §9). All names and numbers are obviously fake.

Writes tools/out/seed/*.jpg (gallery images), tools/out/seed_pdf/*.pdf (for the "Add PDF" picker) and
tools/out/seed/seed_manifest.json with the expected document type for every file (used by the Phase 2
gate: ≥ 85% correctly typed, non-documents skipped).

Indic text needs Pillow with libraqm (HarfBuzz + FriBiDi) for correct shaping:
  brew install fribidi
  DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib .venv/bin/python tools/make_seed_docs.py
"""
import datetime as dt
import json
import pathlib
import random

import qrcode
from PIL import Image, ImageDraw, ImageFont, features

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "tools/out/seed"
OUT_PDF = ROOT / "tools/out/seed_pdf"

LATIN = "/System/Library/Fonts/Supplemental/Arial.ttf"
LATIN_BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
DEVA = "/System/Library/Fonts/Kohinoor.ttc"
TELUGU = "/System/Library/Fonts/KohinoorTelugu.ttc"

TODAY = dt.date.today()
rnd = random.Random(7)


def verhoeff_check(digits: str) -> int:
    d = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5], [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
         [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1], [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
         [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
    p = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
         [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1], [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]
    inv = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]
    c = 0
    for i, ch in enumerate(reversed(digits)):
        c = d[c][p[(i + 1) % 8][int(ch)]]
    return inv[c]


def fake_aadhaar() -> str:
    prefix = str(rnd.randint(2, 9)) + "".join(str(rnd.randint(0, 9)) for _ in range(10))
    n = prefix + str(verhoeff_check(prefix))
    return f"{n[:4]} {n[4:8]} {n[8:]}"


def font(path, size, index=0):
    layout = ImageFont.Layout.RAQM if features.check("raqm") else ImageFont.Layout.BASIC
    return ImageFont.truetype(path, size, index=index, layout_engine=layout)


class Page:
    def __init__(self, w=1240, h=1754, bg="white"):
        self.img = Image.new("RGB", (w, h), bg)
        self.d = ImageDraw.Draw(self.img)
        self.y = 70

    def line(self, text, size=30, path=LATIN, x=80, gap=14, fill="black"):
        f = font(path, size)
        self.d.text((x, self.y), text, font=f, fill=fill)
        self.y += int(size * 1.25) + gap
        return self

    def segs(self, parts, size=30, x=80, gap=14):
        """One line mixing scripts: [(text, font_path), ...]."""
        cx = x
        for text, path in parts:
            f = font(path, size)
            self.d.text((cx, self.y), text, font=f, fill="black")
            cx += int(self.d.textlength(text, font=f)) + 12
        self.y += int(size * 1.25) + gap
        return self

    def rule(self):
        self.d.line((80, self.y, self.img.width - 80, self.y), fill="#888", width=2)
        self.y += 24
        return self

    def save(self, name):
        self.img.save(OUT / name, quality=92)
        return name


def id_card(w=1016, h=640, bg="#f4f1e8"):
    p = Page(w, h, bg)
    p.y = 40
    return p


manifest = []


def add(name, doc_type, is_document=True, expiry=None, lang="en"):
    manifest.append({"file": name, "expected_type": doc_type, "is_document": is_document,
                     "expiry": expiry.isoformat() if expiry else None, "language": lang})


def aadhaar():
    p = id_card()
    p.segs([("भारत सरकार", DEVA)], size=34, x=330)
    p.line("GOVERNMENT OF INDIA", size=30, path=LATIN_BOLD, x=330)
    p.d.rectangle((60, 170, 290, 450), outline="#555", width=3)  # photo placeholder
    p.y = 190
    p.segs([("टेस्ट कुमार सैंपल", DEVA)], size=30, x=330)
    p.line("Test Kumar Sample", size=30, x=330)
    p.segs([("जन्म तिथि", DEVA), ("/ DOB: 01/01/1990", LATIN)], size=28, x=330)
    p.segs([("पुरुष", DEVA), ("/ MALE", LATIN)], size=28, x=330)
    p.y = 500
    p.line(fake_aadhaar(), size=44, path=LATIN_BOLD, x=330)
    p.segs([("मेरा आधार, मेरी पहचान", DEVA)], size=26, x=330)
    qr = qrcode.make("FAKE SAMPLE DATA - NOT A REAL AADHAAR").resize((190, 190))
    p.img.paste(qr, (790, 150))
    add(p.save("aadhaar_front.jpg"), "AADHAAR", lang="hi")


def pan():
    p = id_card(bg="#e8f0f8")
    p.segs([("आयकर विभाग", DEVA), ("INCOME TAX DEPARTMENT", LATIN_BOLD)], size=30, x=60)
    p.segs([("भारत सरकार", DEVA), ("GOVT. OF INDIA", LATIN_BOLD)], size=26, x=60)
    p.segs([("स्थायी लेखा संख्या कार्ड", DEVA), ("Permanent Account Number Card", LATIN)], size=24, x=60)
    p.line("TESTP1234K", size=42, path=LATIN_BOLD, x=60)
    p.segs([("नाम / Name", DEVA)], size=22, x=60, gap=2)
    p.line("TEST KUMAR SAMPLE", size=30, x=60)
    p.segs([("पिता का नाम / Father's Name", DEVA)], size=22, x=60, gap=2)
    p.line("RAM SAMPLE", size=30, x=60)
    p.line("Date of Birth 01/01/1990", size=26, x=60)
    add(p.save("pan_card.jpg"), "PAN", lang="hi")


def salary_slip(months_ago: int):
    first = (TODAY.replace(day=1) - dt.timedelta(days=1)).replace(day=1)
    for _ in range(months_ago - 1):
        first = (first - dt.timedelta(days=1)).replace(day=1)
    month = first.strftime("%B %Y")
    p = Page()
    p.line("SAMPLE SOFTWARE PVT LTD", size=44, path=LATIN_BOLD)
    p.line(f"Payslip for the month of {month}", size=34)
    p.rule()
    p.line("Employee Name: TEST KUMAR SAMPLE     Employee ID: EMP-0042")
    p.line("Designation: Software Engineer     PAN: TESTP1234K")
    p.rule()
    p.line("Earnings                         Deductions", path=LATIN_BOLD)
    p.line("Basic          40,000.00         Provident Fund     4,800.00")
    p.line("HRA            16,000.00         Professional Tax     200.00")
    p.line("Special         9,000.00         Income Tax         5,000.00")
    p.rule()
    p.line("Gross Earnings 65,000.00         Total Deductions  10,000.00")
    p.line("Net Pay: 55,000.00", size=36, path=LATIN_BOLD)
    add(p.save(f"salary_slip_{first:%Y_%m}.jpg"), "SALARY_SLIP")


def bank_statement():
    start = (TODAY.replace(day=1) - dt.timedelta(days=1)).replace(day=1)
    end = TODAY.replace(day=1) - dt.timedelta(days=1)
    p = Page()
    p.line("SAMPLE BANK LTD", size=46, path=LATIN_BOLD)
    p.line("Statement of Account", size=36)
    p.line(f"Account No: 000123456789     IFSC: SMPL0001234")
    p.line(f"Statement period {start:%d/%m/%Y} to {end:%d/%m/%Y}")
    p.rule()
    p.line("Date         Description              Debit      Credit     Balance", path=LATIN_BOLD, size=26)
    bal = 52000
    p.line(f"{start:%d/%m/%Y}   Opening Balance                                  {bal:,}.00", size=26)
    for i in range(8):
        day = start + dt.timedelta(days=3 * i + 1)
        amt = rnd.choice([1200, 3500, 800, 15000, 250])
        credit = i == 3
        bal = bal + (55000 if credit else -amt)
        p.line(f"{day:%d/%m/%Y}   {'SALARY CREDIT' if credit else 'UPI/SHOP/SAMPLE':<24} {'' if credit else f'{amt:,}.00':>10} {'55,000.00' if credit else '':>10} {bal:,}.00", size=26)
    p.line(f"{end:%d/%m/%Y}   Closing Balance                                  {bal:,}.00", size=26)
    add(p.save("bank_statement.jpg"), "BANK_STATEMENT")


def health_insurance():
    end = TODAY + dt.timedelta(days=7)
    start = end - dt.timedelta(days=364)
    p = Page()
    p.line("SAMPLE HEALTH INSURANCE CO. LTD", size=40, path=LATIN_BOLD)
    p.line("Health Insurance Policy Schedule", size=34)
    p.rule()
    p.line("Policy No: 2856/SMPL/0042/000")
    p.line("Insured Name: Test Kumar Sample")
    p.line("Plan: Family Floater     Sum Insured: Rs. 5,00,000")
    p.line(f"Period of Insurance: {start:%d/%m/%Y} to {end:%d/%m/%Y}")
    p.line("Cashless hospitalisation at network hospitals. TPA: Sample TPA")
    p.line("Premium: Rs. 18,500")
    add(p.save("health_insurance.jpg"), "HEALTH_INSURANCE", expiry=end)


def car_insurance():
    end = TODAY - dt.timedelta(days=40)
    start = end - dt.timedelta(days=364)
    p = Page()
    p.line("SAMPLE GENERAL INSURANCE LTD", size=40, path=LATIN_BOLD)
    p.line("Private Car Package Policy", size=34)
    p.rule()
    p.line("Policy Number: MOT/SMPL/2025/0099")
    p.line("Registration No: TS 09 EA 1234     Chassis No: SMPLCHS0001")
    p.line("Insured Declared Value (IDV): Rs. 4,20,000")
    p.line(f"Period of Insurance: {start:%d/%m/%Y} to {end:%d/%m/%Y}")
    add(p.save("car_insurance_expired.jpg"), "VEHICLE_INSURANCE", expiry=end)


def hospital_bill():
    p = Page()
    p.line("SAMPLE MULTISPECIALITY HOSPITAL", size=40, path=LATIN_BOLD)
    p.line("Final Bill (IP Bill)", size=34)
    p.rule()
    p.line("Patient Name: Test Kumar Sample     UHID: SMPL-778899")
    p.line(f"Date of Admission: {TODAY - dt.timedelta(days=20):%d/%m/%Y}   Date of Discharge: {TODAY - dt.timedelta(days=17):%d/%m/%Y}")
    p.line("Room Charges            12,000.00")
    p.line("Pharmacy                 6,450.00")
    p.line("Investigations           4,300.00")
    p.line("Total Amount: Rs. 22,750.00", size=34, path=LATIN_BOLD)
    add(p.save("hospital_bill.jpg"), "HOSPITAL_BILL")


def medical_report():
    p = Page()
    p.line("SAMPLE DIAGNOSTICS LABORATORY", size=40, path=LATIN_BOLD)
    p.line("Test Report - Complete Blood Count", size=34)
    p.rule()
    p.line(f"Patient: Test Kumar Sample   Sample Collected: {TODAY - dt.timedelta(days=18):%d/%m/%Y}   Specimen: Blood")
    p.line("Test                 Result      Biological Reference Range", path=LATIN_BOLD)
    p.line("Haemoglobin          13.8 g/dL   13.0 - 17.0")
    p.line("WBC Count            7,200       4,000 - 11,000")
    p.line("Platelet Count       2.4 lakh    1.5 - 4.1 lakh")
    p.line("Fasting Glucose      92 mg/dL    70 - 100")
    add(p.save("medical_report.jpg"), "MEDICAL_REPORT")


def telugu_electricity_bill():
    due = TODAY + dt.timedelta(days=12)
    p = Page()
    p.segs([("విద్యుత్ బిల్లు", TELUGU), ("SAMPLE POWER DISTRIBUTION CO", LATIN_BOLD)], size=36)
    p.segs([("వినియోగదారు సంఖ్య:", TELUGU), ("1234567890", LATIN)], size=30)
    p.segs([("బిల్లు తేదీ:", TELUGU), (f"{TODAY - dt.timedelta(days=3):%d/%m/%Y}", LATIN)], size=30)
    p.segs([("వినియోగించిన యూనిట్లు:", TELUGU), ("245", LATIN)], size=30)
    p.segs([("చెల్లించవలసిన మొత్తం:", TELUGU), ("Rs. 1,250.00", LATIN)], size=30)
    p.segs([("గడువు తేదీ:", TELUGU), (f"{due:%d/%m/%Y}", LATIN)], size=30)
    p.line("Electricity Bill - Consumer No 1234567890 - Units consumed 245", size=26)
    add(p.save("electricity_bill_te.jpg"), "UTILITY_BILL", expiry=due, lang="te")


def abha_card():
    p = id_card(bg="#eef7ee")
    p.line("Ayushman Bharat Health Account (ABHA)", size=30, path=LATIN_BOLD, x=60)
    p.segs([("आभा स्वास्थ्य खाता", DEVA)], size=28, x=60)
    p.line("Name: Test Kumar Sample", size=28, x=60)
    p.line("ABHA Number: 91-4242-4242-4242", size=32, path=LATIN_BOLD, x=60)
    p.line("ABHA Address: testsample@abdm", size=28, x=60)
    p.line("National Health Authority", size=24, x=60)
    add(p.save("abha_card.jpg"), "HEALTH_ID_ABHA", lang="hi")


def employment_letter():
    p = Page()
    p.line("SAMPLE SOFTWARE PVT LTD", size=44, path=LATIN_BOLD)
    p.line("Employment Letter", size=36)
    p.rule()
    p.line(f"Date: {TODAY - dt.timedelta(days=30):%d/%m/%Y}")
    p.line("To whom it may concern")
    p.line("This is to certify that Test Kumar Sample is employed with us")
    p.line("as Software Engineer since the date of joining 01/07/2021.")
    p.line("Annual CTC: Rs. 7,80,000")
    p.line("HR Manager, Human Resources")
    add(p.save("employment_letter.jpg"), "EMPLOYMENT_LETTER")


def non_documents():
    # Landscape: sky gradient, sun, hills.
    img = Image.new("RGB", (1600, 1200))
    d = ImageDraw.Draw(img)
    for y in range(1200):
        d.line((0, y, 1600, y), fill=(90 + y // 12, 150 + y // 20, 235 - y // 10) if y < 700 else (60, 140 - (y - 700) // 8, 60))
    d.ellipse((1150, 150, 1350, 350), fill=(255, 220, 90))
    d.polygon([(0, 750), (400, 520), (800, 760), (1200, 560), (1600, 760), (1600, 1200), (0, 1200)], fill=(50, 120, 60))
    img.save(OUT / "photo_landscape.jpg", quality=90)
    add("photo_landscape.jpg", None, is_document=False)

    # Food: plate with colourful items.
    img = Image.new("RGB", (1400, 1400), (120, 80, 50))
    d = ImageDraw.Draw(img)
    d.ellipse((150, 150, 1250, 1250), fill=(245, 245, 240))
    for _ in range(40):
        x, y, r = rnd.randint(350, 1050), rnd.randint(350, 1050), rnd.randint(30, 80)
        d.ellipse((x - r, y - r, x + r, y + r), fill=rnd.choice([(230, 160, 30), (200, 60, 40), (90, 160, 60), (250, 230, 180)]))
    img.save(OUT / "photo_food.jpg", quality=90)
    add("photo_food.jpg", None, is_document=False)

    # Selfie-like: a face.
    img = Image.new("RGB", (1200, 1600), (170, 200, 230))
    d = ImageDraw.Draw(img)
    d.ellipse((300, 350, 900, 1100), fill=(224, 172, 130))
    d.ellipse((440, 600, 520, 660), fill="white"); d.ellipse((470, 615, 505, 650), fill=(60, 40, 30))
    d.ellipse((680, 600, 760, 660), fill="white"); d.ellipse((710, 615, 745, 650), fill=(60, 40, 30))
    d.arc((470, 800, 730, 950), start=20, end=160, fill=(150, 60, 60), width=12)
    d.pieslice((280, 250, 920, 700), start=180, end=360, fill=(40, 30, 25))
    d.rectangle((250, 1150, 950, 1600), fill=(40, 90, 160))
    img.save(OUT / "selfie_like.jpg", quality=90)
    add("selfie_like.jpg", None, is_document=False)


def statement_pdf():
    pages = []
    for i in range(2):
        p = Page()
        p.line("SAMPLE BANK LTD", size=46, path=LATIN_BOLD)
        p.line(f"Statement of Account - page {i + 1} of 2", size=34)
        p.line("Account No: 000987654321     IFSC: SMPL0009876")
        for k in range(12):
            p.line(f"{(TODAY - dt.timedelta(days=60 - 4 * k)):%d/%m/%Y}   UPI/SAMPLE/{k:04d}      {rnd.randint(100, 5000):,}.00", size=26)
        pages.append(p.img)
    pages[0].save(OUT_PDF / "bank_statement_2pages.pdf", save_all=True, append_images=pages[1:])


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    OUT_PDF.mkdir(parents=True, exist_ok=True)
    if not features.check("raqm"):
        print("WARNING: Pillow has no raqm layout; Hindi/Telugu text will be shaped incorrectly (see module docstring).")
    aadhaar(); pan()
    for m in (1, 2, 3):
        salary_slip(m)
    bank_statement(); health_insurance(); car_insurance(); hospital_bill(); medical_report()
    telugu_electricity_bill(); abha_card(); employment_letter()
    non_documents(); statement_pdf()
    (OUT / "seed_manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    docs = sum(1 for m in manifest if m["is_document"])
    print(f"wrote {len(manifest)} images ({docs} documents, {len(manifest) - docs} non-documents) to {OUT}, 1 PDF to {OUT_PDF}")


if __name__ == "__main__":
    main()
