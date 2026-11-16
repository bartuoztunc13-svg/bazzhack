"""Optional direct posting to YouTube through Google's normal sign-in (OAuth).

Turned on only when client_secret.json exists next to this file (see README).
Your password is typed into Google's own page, never into this app.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT_SECRET = os.environ.get("YT_CLIENT_SECRET", os.path.join(HERE, "client_secret.json"))
TOKEN_FILE = os.path.join(HERE, "data", "youtube_token.json")
SCOPES = ["https://www.googleapis.com/auth/youtube.upload",
          "https://www.googleapis.com/auth/youtube.readonly"]


def enabled():
    return os.path.exists(CLIENT_SECRET)


def _flow(redirect_uri, state=None):
    from google_auth_oauthlib.flow import Flow
    flow = Flow.from_client_secrets_file(CLIENT_SECRET, scopes=SCOPES, state=state)
    flow.redirect_uri = redirect_uri
    return flow


def auth_url(redirect_uri):
    flow = _flow(redirect_uri)
    url, state = flow.authorization_url(access_type="offline", prompt="consent",
                                        include_granted_scopes="true")
    return url, state, getattr(flow, "code_verifier", None)


def finish_auth(redirect_uri, state, code_verifier, response_url):
    flow = _flow(redirect_uri, state)
    if code_verifier:
        flow.code_verifier = code_verifier
    flow.fetch_token(authorization_response=response_url)
    os.makedirs(os.path.dirname(TOKEN_FILE), exist_ok=True)
    with open(TOKEN_FILE, "w") as f:
        f.write(flow.credentials.to_json())


def _creds():
    if not os.path.exists(TOKEN_FILE):
        return None
    from google.oauth2.credentials import Credentials
    from google.auth.transport.requests import Request
    creds = Credentials.from_authorized_user_info(json.load(open(TOKEN_FILE)), SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
        with open(TOKEN_FILE, "w") as f:
            f.write(creds.to_json())
    return creds if creds.valid else None


def sign_out():
    if os.path.exists(TOKEN_FILE):
        os.remove(TOKEN_FILE)


def channel_name():
    creds = _creds()
    if not creds:
        return None
    from googleapiclient.discovery import build
    yt = build("youtube", "v3", credentials=creds, cache_discovery=False)
    items = yt.channels().list(part="snippet", mine=True).execute().get("items", [])
    return items[0]["snippet"]["title"] if items else "Your channel"


def upload(path, title, description, tags, privacy, progress=None):
    creds = _creds()
    if not creds:
        raise RuntimeError("Not signed in to YouTube")
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload
    yt = build("youtube", "v3", credentials=creds, cache_discovery=False)
    body = {
        "snippet": {"title": title[:100], "description": description[:5000],
                    "tags": tags, "categoryId": "22"},
        "status": {"privacyStatus": privacy, "selfDeclaredMadeForKids": False},
    }
    req = yt.videos().insert(part="snippet,status", body=body,
                             media_body=MediaFileUpload(path, chunksize=8 * 1024 * 1024, resumable=True))
    resp = None
    while resp is None:
        status, resp = req.next_chunk()
        if status and progress:
            progress(int(status.progress() * 100))
    return resp["id"]
