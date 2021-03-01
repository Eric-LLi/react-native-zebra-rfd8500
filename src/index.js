import { NativeModules, NativeEventEmitter } from 'react-native';

const { ZebraRfd8500 } = NativeModules;

const events = {};

const eventEmitter = new NativeEventEmitter(ZebraRfd8500);

ZebraRfd8500.on = (event, handler) => {
	const eventListener = eventEmitter.addListener(event, handler);

	events[event] =  events[event] ? [...events[event], eventListener]: [eventListener];
};

ZebraRfd8500.off = (event) => {
	if (Object.hasOwnProperty.call(events, event)) {
		const eventListener = events[event].shift();

		if(eventListener) eventListener.remove();
	}
};

ZebraRfd8500.removeAll = (event) => {
	if (Object.hasOwnProperty.call(events, event)) {
		eventEmitter.removeAllListeners(event);

		events[event] = [];
	}
}

export default ZebraRfd8500;
