# Nojre

A WIFI walkie talkie designed for cycling with my friends.

## Intro

This app will broadcast the audio input from your Android phone using UDP multicast. By connecting to the same WIFI, I can chat with my friends when cycling without rely on any internet services.

This app features of encryption and auto discover, thus will prevent someone else tapping on the same channel without correct password. Also it will automatically found peers on the same channel using the same password.

Since the app is designed for cycling, it will automatically use Bluetooth microphone when it can.

## Security

When installing the app, Play Protect will complain about unknown developer signature. I think it's because this app fetch your audio and send it over the internet, thus triggered the Google Play's alarm. I highly suggest you read the source code and see if it's secure enough to use in your situation. And you may build and sign the apk file by yourself.

## Spec

This app uses a fixed 16KHz sample rate, 16bit signed little endian encoded PCM in mono format for audio. I think this is a acceptable settings for both quality and bandwidth. Unless we humans evolve new vocal organs, I think it's good enough to stick with it.

The audio bandwidth is `16000 * 16 * 1 = 256kpbs`, the protocol is:

### Encryption

Every packet must be encrypted before sent. The first byte of encrypted packet marks the encryption type it uses. The payload is encrypted protocol packet.

#### Key

In this app, the encryption key is calculated by `SHA-256` of the text password. The key size is 256 bits.

#### 0x01

Currently this is the only one we have. According to [Android documentation's recommendation](https://developer.android.com/guide/topics/security/cryptography#choose-algorithm), here I use `AES/GCM/NoPadding`, 256 bits of key, 96 bits of IV (or you may call it nonce).

The packet start with prefix `0x01` (1 byte), then followed by 12 bytes of IV (or nonce), then is the encrypted cipher text (total size is `packet.length - 1 - 12`).

The nonce/IV is randomly selected by `Cipher` and should not be used as a packet number/counter. The UDP multicast in local network is fairly stable and we don't need to considering out-of-order issue.

### Protocol packet

After decrypted the encrypted payload, you get the protocol packet. It's used by app to send out audio data and other info about clients. All protocol packet start with 1 byte of prefix marking what kind of packet it is.

#### Advertise (0x01)

This packet advertise nickname about ourselves. Currently there are no access control over the app, since it rely on a local portable WIFI (we're talking about cycling, you do prefer a portable with a power bank, right? Don't tell me you're carrying a 5V to 12V converter), so I will assume you can control the access of the WIFI.

TODO: In case of a public WIFI, I can introduce a signing feature to identify different peers, but that will introduce a reply attack.

This packet simply send out the UTF8 encoded nickname of our peer. This will provide a human-friendly name to other users instead of your id address.

Format: 1byte of `0x01` for prefix, then followed by UTF8 bytes (total size: `packet.length - 1`)

#### Audio (0x02)

This packet send out the audio data using PCM signed 16 bit little endian encoding. This will ensure the data is not truncated: each sample is 16b thus the data size is even number. No half of a sample. 

The data size is decided by `AudioRecord.getMinBufferSize`, so each client might have different packet size. But the max size is set to 60KB to count protocol overhead, where the max UDP packet size is 65535.

Format: 1 byte of `0x02` for prefix, then followed by data (total size: `packet.length - 1`).

### APP Structure

This app is fairly simple: Two activities, one for settings (nickname, channel, password, etc.); one for peers details (see who is in broadcast).

The core function relies on a foreground service, which ensures the broadcast won't be killed unless user clicks the stop button. The service will try to enable Bluetooth SCO if there are any BT SCO device available. Then it will join the UDP multicast group and setting up network configurations. Finally, it will start several threads:

1. broadcast thread: Fetch your audio input and send out using UDP. It also handle advertising packet since it's the only thread that should write to the UDP socket.
2. receive thread: Read all others UDP packet from the UDP socket, decrypted it, find the local peer info based on the source IP (assuming each IP is a client) , and let the peer handle it.
   1. For advertise packet, the peer update it's nickname
   2. For audio packet, it parse it into Short and offer them to a local queue
3. loop thread: run scheduled tasks using Kotlin coroutine.
   1. loop through all peers and delete dead peers (5s we don't hear from them)
4. mixer thread: Take buffered audio samples from peers' queue and mixing them into one audio stream. Here we apply volume factor (so you can mute someone), add them together (in range of -1 to 1), uniform them and write to audio output.

## Known issues

According my test, Android phone will work fine if you DO NOT use the soft AP. Once you enabled the Soft AP, the host device will no longer receive the UDP multicast packet, but clients are fine. So you may need a separate WIFI, for example the portable WIFI, or a spare/old phone to host the AP.

### How to choose a WIFI Router

During my daily usage experience, a WIFI with no internet will pretty much disabled all your app. Even the navigation app. I use [OsmAnd](https://osmand.net) for offline navigation. Not the best map app, but it's the best offline map app.

If you don't want to suffer from open source but low quality map data, you might want something like a mobile hotspot, which is a separate device connecting to the cellular network and providing WIFI with actual internet access.

Based on my estimate, a peer will use no more than 300Kbps of data when running this app. So one peer is 300Kbps of upload, and `N-1` times of 300Kbps for others to download the audio. So each peer is consuming `N * 300 Kbps` of bandwidth. With the worst 150Mbps mobile hotspot device, it can handle about 24 peers using the app at the same time. Even considering the client devices (aka your phone) cannot use the whole 150Mbps, let's say it can only use 100Mbps (reported by my Samsung phone), it still can support more than 10 peers. (10 peers only need 30Mbps of total bandwidth)

If you're a large group of rider using this app, then I suggest each of you get a HAM license and using handheld transceiver for better communication. (Or install a powerful WIFI router on the maintenance car)

### My audio is suddenly become so small/windy/inaudible

That might because your Bluetooth device is disconnected. The app will automatically downgrade your audio I/O from Bluetooth back to normal audio&mic when Bluetooth device is disconnected. You may simply click the start button again, the app will reinitialize the Bluetooth device and use it. 

### My system is keep killing the app

Well, god bless you. Read this: [Don't kill my app](https://dontkillmyapp.com).

## Usage and License

This app is designed for cycling with my friend. So I will keep update this app as long as I still need it. Also I'm glad to hear feedbacks and contributions if you have similar needs (cycling with friend, etc.). But do notice that I'm not paid to maintain this app, so your request might not be fulfilled. (Which also means you can use this app for free!)

This repo is licensed in AGPL v3. So, (to stealers) respect the license.