import asyncio, json, sys
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.dirname(__import__('os').path.abspath(__file__))))
from pydglab_ws import DGLabWSServer, Channel, StrengthOperationType
import websockets

async def fake_app(uri, client_id, got):
    async with websockets.connect(uri) as ws:
        first = json.loads(await ws.recv()); target_id = first["clientId"]
        await ws.send(json.dumps({"type":"bind","clientId":client_id,"targetId":target_id,"message":"DGLAB"}))
        print("fake app bind reply:", await ws.recv())
        await ws.send(json.dumps({"type":"msg","clientId":client_id,"targetId":target_id,"message":"strength-5+0+20+20"}))
        for _ in range(3):
            m = json.loads(await ws.recv()); got.append(m["message"]); print("fake app got:", m["message"][:80])

async def main():
    got = []
    async with DGLabWSServer("127.0.0.1", 5679, heartbeat_interval=None) as server:
        client = server.new_local_client()
        print("QR:", client.get_qrcode("ws://127.0.0.1:5679"))
        app = asyncio.create_task(fake_app("ws://127.0.0.1:5679", str(client.client_id), got))
        await client.ensure_bind(); print("bound to", client.target_id)
        data = await client.recv_data(); print("strength data:", data)
        await client.set_strength(Channel.A, StrengthOperationType.SET_TO, 5)
        await client.add_pulses(Channel.A, ((30,30,30,30),(100,100,100,100)))
        await client.clear_pulses(Channel.A)
        await asyncio.wait_for(app, 3)
    assert got == ["strength-1+2+5", 'pulse-A:["1e1e1e1e64646464"]', "clear-1"], got
    print("SOCKET SMOKE OK")
asyncio.run(main())
