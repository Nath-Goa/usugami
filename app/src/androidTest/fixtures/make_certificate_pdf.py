"""Makes assets/certificate.pdf: alpha.pdf encrypted for a certificate (Adobe.PubSec) instead of a
password. PDFer can't open such PDFs (PDFBox needs BouncyCastle for them, which isn't bundled), and
PdfToolsDeviceTest checks that it says so instead of crashing.

    pip install pyhanko==0.38.0 cryptography==50.0.2
    python app/src/androidTest/fixtures/make_certificate_pdf.py

The certificate is a throwaway self-signed one; its private key is not kept.
"""
import datetime
from pathlib import Path

from asn1crypto import x509 as asn1_x509
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from pyhanko.pdf_utils.reader import PdfFileReader
from pyhanko.pdf_utils.writer import copy_into_new_writer

ASSETS = Path(__file__).resolve().parent.parent / "assets"


def throwaway_certificate() -> asn1_x509.Certificate:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PDFer test recipient")])
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(start)
        .not_valid_after(start + datetime.timedelta(days=3650))
        .add_extension(
            x509.KeyUsage(
                digital_signature=False, content_commitment=False, key_encipherment=True,
                data_encipherment=False, key_agreement=False, key_cert_sign=False,
                crl_sign=False, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )
    return asn1_x509.Certificate.load(cert.public_bytes(serialization.Encoding.DER))


def main() -> None:
    with open(ASSETS / "alpha.pdf", "rb") as source:
        writer = copy_into_new_writer(PdfFileReader(source))
    writer.encrypt_pubkey([throwaway_certificate()])
    with open(ASSETS / "certificate.pdf", "wb") as out:
        writer.write(out)


if __name__ == "__main__":
    main()
