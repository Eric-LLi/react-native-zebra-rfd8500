import { NativeModules, NativeEventEmitter } from 'react-native';

const { ZebraRfd8500 } = NativeModules;

const events = {};

const eventEmitter = new NativeEventEmitter(ZebraRfd8500);

ZebraRfd8500.on = (event, handler) => {
	const eventListener = eventEmitter.addListener(event, handler);

	events[event] = eventListener;
};

ZebraRfd8500.off = (event) => {
	if (events.hasOwnProperty(event)) {
		const eventListener = events[event];

		eventListener.remove();

		delete events[event];
	}
};

export default ZebraRfd8500;
