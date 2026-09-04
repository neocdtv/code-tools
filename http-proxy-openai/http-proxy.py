import json
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse, Response
import httpx

app = FastAPI()

# Target where llama-server is running
TARGET_LLAMA_SERVER = "http://127.0.0.1:8082"

# TARGET_LLAMA_SERVER = "http://192.168.178.137:8080"

# Global counter for fake commit messages
commit_counter = 1

# Set infinite read timeout for long LLM prompt evaluations
timeout_config = httpx.Timeout(
    connect=10.0,
    read=None,
    write=30.0,
    pool=10.0
)

client = httpx.AsyncClient(
    base_url=TARGET_LLAMA_SERVER,
    timeout=timeout_config
)

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy(request: Request, path: str):
    global commit_counter
    body = await request.body()
    
    print(f"\n==================================================")
    print(f"[REQUEST] {request.method} /{path}")
    print(f"==================================================")
    
    json_payload = None
    if body:
        try:
            json_payload = json.loads(body)
            print("[PAYLOAD JSON]:")
            print(json.dumps(json_payload, indent=2, ensure_ascii=False))
        except Exception:
            print(f"[PAYLOAD RAW]: {body.decode('utf-8', errors='ignore')}")

    # --- 1. INTERCEPT COMMIT MESSAGE GENERATION ---
    if json_payload and path.endswith("/chat/completions"):
        messages = json_payload.get("messages", [])
        if messages and messages[0].get("role") == "system":
            system_content = messages[0].get("content", "")
            
            # Match system prompt patterns used by aider for commit messages
            if "concise, one-line Git commit messages" in system_content or "Git commit messages based on the provided diffs" in system_content:
                
                mock_commit_msg = f"chore: commit{commit_counter}"
                commit_counter += 1
                
                print(f"\n\033[32m? [FAST INTERCEPT] Skipping LLM call. Returning: '{mock_commit_msg}'\033[0m\n")

                if json_payload.get("stream", False):
                    sse_payload = (
                        f'data: {{"id":"chatcmpl-fast","choices":[{"delta":{{"content":"{mock_commit_msg}"}},"finish_reason":null}]}}\n\n'
                        f'data: {{"id":"chatcmpl-fast","choices":[{"delta":{{}},"finish_reason":"stop"}]}}\n\n'
                        f'data: [DONE]\n\n'
                    )
                    return Response(content=sse_payload, media_type="text/event-stream")
                else:
                    res_json = {
                        "id": "chatcmpl-fast",
                        "object": "chat.completion",
                        "choices": [{"message": {"role": "assistant", "content": mock_commit_msg}, "finish_reason": "stop"}]
                    }
                    return Response(content=json.dumps(res_json), media_type="application/json")

    # --- 2. FORWARD REGULAR PROMPTS TO LLAMA-SERVER ---
    headers = dict(request.headers)
    headers.pop("host", None)

    req = client.build_request(
        method=request.method,
        url=path,
        headers=headers,
        content=body,
        params=request.query_params
    )

    try:
        response = await client.send(req, stream=True)
    except httpx.RequestError as exc:
        print(f"\033[31m[PROXY ERROR] Failed to connect to llama-server: {exc}\033[0m")
        return StreamingResponse(
            content=iter([b"Error connecting to backend llama-server"]),
            status_code=502
        )

    # --- 3. STREAM & PARSE LLM RESPONSE CHUNKS ---
    async def stream_generator():
        print(f"\n--- [RESPONSE STREAMING] Status: {response.status_code} ---")
        buffer = ""

        try:
            async for chunk in response.aiter_bytes():
                # Yield raw chunk immediately back to Aider
                yield chunk

                # Buffer and parse for proxy terminal output
                buffer += chunk.decode('utf-8', errors='ignore')
                lines = buffer.split("\n")
                buffer = lines.pop()

                for line in lines:
                    line = line.strip()
                    if not line.startswith("data: ") or line == "data: [DONE]":
                        continue

                    try:
                        data = json.loads(line[6:])
                        choices = data.get("choices", [])
                        if not choices:
                            continue

                        delta = choices[0].get("delta", {})

                        # Print reasoning tokens in CYAN
                        if "reasoning_content" in delta and delta["reasoning_content"]:
                            print(f"\033[36m{delta['reasoning_content']}\033[0m", end="", flush=True)

                        # Print content tokens in DEFAULT COLOR
                        elif "content" in delta and delta["content"]:
                            print(delta["content"], end="", flush=True)

                    except json.JSONDecodeError:
                        pass

        except (httpx.StreamError, httpx.HTTPError) as exc:
            print(f"\n\033[31m[STREAM INTERRUPTED]: {exc}\033[0m")
        finally:
            await response.aclose()
            print("\n==================================================")
            print("--- [STREAM END] ---")
            print("==================================================")

    return StreamingResponse(
        stream_generator(),
        status_code=response.status_code,
        headers=dict(response.headers)
    )
