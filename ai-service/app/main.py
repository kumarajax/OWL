from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="OWL Drive AI Service", version="0.1.0")


class SummaryRequest(BaseModel):
    object_id: str
    text: str


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/v1/summarize")
def summarize(request: SummaryRequest) -> dict[str, object]:
    words = request.text.split()
    preview = " ".join(words[:80])
    return {
        "objectId": request.object_id,
        "summary": preview,
        "classification": "UNCLASSIFIED",
        "sensitiveFindings": [],
    }

