import { net as electronNet } from 'electron';
import http from 'http';
import net from 'net';

import log from '/@/main/logger';

// Some ISPs block YouTube only at the DNS level: browsers get through with
// DNS-over-HTTPS, plain programs like yt-dlp do not. yt-dlp is pointed at this
// local proxy, which resolves host names over DoH and then connects directly.

const DOH_SERVERS = ['https://cloudflare-dns.com/dns-query', 'https://dns.google/resolve'];

const cache = new Map<string, { expires: number; ip: string }>();

const resolve = async (host: string): Promise<string> => {
    if (net.isIP(host)) return host;
    const cached = cache.get(host);
    if (cached && cached.expires > Date.now()) return cached.ip;

    for (const server of DOH_SERVERS) {
        try {
            const response = await electronNet.fetch(
                `${server}?name=${encodeURIComponent(host)}&type=A`,
                {
                    headers: { accept: 'application/dns-json' },
                },
            );
            const body = (await response.json()) as {
                Answer?: { data: string; TTL: number; type: number }[];
            };
            const answer = body.Answer?.find((a) => a.type === 1);
            if (answer) {
                cache.set(host, {
                    expires: Date.now() + Math.max(60, answer.TTL) * 1000,
                    ip: answer.data,
                });
                return answer.data;
            }
        } catch {
            // try the next DoH server
        }
    }
    return host; // fall back to the system resolver
};

let proxyUrl: null | Promise<string> = null;

export const getDohProxyUrl = () => {
    proxyUrl ??= new Promise<string>((resolveUrl, reject) => {
        const server = http.createServer((req, res) => {
            // plain http (rare for yt-dlp): forward the request
            const target = new URL(req.url ?? '');
            resolve(target.hostname)
                .then((ip) => {
                    const upstream = http.request(
                        {
                            headers: req.headers,
                            host: ip,
                            method: req.method,
                            path: target.pathname + target.search,
                            port: target.port || 80,
                        },
                        (upstreamRes) => {
                            res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
                            upstreamRes.pipe(res);
                        },
                    );
                    upstream.on('error', () => res.destroy());
                    req.pipe(upstream);
                })
                .catch(() => res.destroy());
        });

        // https: a CONNECT tunnel to the DoH-resolved address
        server.on('connect', (req: http.IncomingMessage, client: net.Socket, head: Buffer) => {
            const [host, port] = (req.url ?? '').split(':');
            resolve(host)
                .then((ip) => {
                    const upstream = net.connect(Number(port) || 443, ip, () => {
                        client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
                        if (head.length) upstream.write(head);
                        upstream.pipe(client);
                        client.pipe(upstream);
                    });
                    upstream.on('error', () => client.destroy());
                    client.on('error', () => upstream.destroy());
                })
                .catch(() => client.destroy());
        });

        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => {
            const { port } = server.address() as net.AddressInfo;
            log.info('DoH proxy for yt-dlp ready', { port });
            resolveUrl(`http://127.0.0.1:${port}`);
        });
    });
    return proxyUrl;
};
