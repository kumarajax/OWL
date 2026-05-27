export default function PrivacyPage() {
  return (
    <main className="mx-auto max-w-3xl px-6 py-12 text-slate-800">
      <h1 className="text-3xl font-semibold text-slate-950">Privacy</h1>
      <p className="mt-4 text-sm text-slate-600">Version 2026-05-26</p>
      <div className="mt-8 space-y-5 leading-7">
        <p>
          OWL Drive collects account details, signup request details, file metadata, storage usage, access logs,
          technical logs, IP address, user agent, and timestamps needed to run and protect the service.
        </p>
        <p>
          File content is stored so the service can provide upload, download, preview, and sharing features. Do not
          upload content unless you are authorized to store it.
        </p>
        <p>
          Information may be used for account approval, operations, troubleshooting, abuse prevention, security,
          capacity planning, and legal compliance. OWL Drive does not sell user data.
        </p>
        <p>
          Information may be disclosed when required by law, to protect the service, to prevent harm, or to respond to
          credible abuse reports. Privacy questions can be sent to{" "}
          <a className="font-semibold text-blue-700" href="mailto:kumarajax@gmail.com">kumarajax@gmail.com</a>.
        </p>
      </div>
    </main>
  );
}
