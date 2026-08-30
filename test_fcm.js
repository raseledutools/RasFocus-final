const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

function base64url(str) {
    return Buffer.from(str).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

const sa = JSON.parse(fs.readFileSync('app/src/main/res/raw/service_account.json'));
const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
const now = Math.floor(Date.now() / 1000);
const claim = base64url(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    exp: now + 3600,
    iat: now
}));

const sign = crypto.createSign('SHA256');
sign.update(`${header}.${claim}`);
sign.end();
const signature = sign.sign(sa.private_key, 'base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');

const jwt = `${header}.${claim}.${signature}`;

const req = https.request({
    hostname: 'oauth2.googleapis.com',
    path: '/token',
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
}, res => {
    let data = '';
    res.on('data', d => data += d);
    res.on('end', () => {
        const token = JSON.parse(data).access_token;
        if(!token) { console.error("No token!", data); return; }
        
        const payload = JSON.stringify({
            message: {
                token: 'dummy',
                data: { type: 'incoming_call', callerName: 'Test' },
                android: { priority: 'high' } // using 'high' lowercase
            }
        });

        const req2 = https.request({
            hostname: 'fcm.googleapis.com',
            path: `/v1/projects/${sa.project_id}/messages:send`,
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            }
        }, res2 => {
            let data2 = '';
            res2.on('data', d => data2 += d);
            res2.on('end', () => console.log(data2));
        });
        req2.write(payload);
        req2.end();
    });
});
req.write(`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`);
req.end();
