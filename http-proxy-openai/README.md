python3 -m venv venv
source ./venv/bin/active
pip install fastapi
pip install httpx
pip install uvicorn
uvicorn http-proxy:app --port 8081
