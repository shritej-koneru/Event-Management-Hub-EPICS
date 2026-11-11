/**
 * App-help topics the campus assistant may cite. Plain facts about how the app works TODAY, each with a stable id
 * ("help:<slug>") so an answer can point to its source. Keep this in step with the app; the assistant must never
 * describe a feature that is not listed here.
 */
export const APP_HELP = Object.freeze([
  {
    id: 'help:save-event',
    title: 'Saving an event',
    text: 'Open an event and tap Save to bookmark it. Saved events appear in Profile and under the Updates tab, where you can read that event\'s announcements and open its help chat. Tap Saved again to remove it. Without internet the change is kept on the phone and sent when the connection returns.'
  },
  {
    id: 'help:help-chat',
    title: 'Help chat with organizers',
    text: 'Under the Updates tab, open a saved event and tap Help to start a chat with that event\'s organizers. A new chat needs a subject (up to 120 characters) and a message (up to 1000 characters). Organizers reply in the same thread. Help chats need an internet connection.'
  },
  {
    id: 'help:announcements',
    title: 'Announcements',
    text: 'Announcements for an event are shown under the Updates tab after you open that saved event. Organizers publish them from the manager website and they appear on your phone without refreshing.'
  },
  {
    id: 'help:map-directions',
    title: 'Map and directions',
    text: 'The Map tab shows the indoor venues. Search for a destination and tap it to see a walking route with steps, the distance and an estimated time. Choose where you are starting from with the From field. Event Details has a Get directions button for the event\'s venue.'
  },
  {
    id: 'help:search',
    title: 'Searching for events',
    text: 'The Home and Events tabs have a search box. You can type words like "music tomorrow" or "free tech events". Filters for date, category, price and accessibility only match events that state that information; events that do not state it are left out and counted.'
  },
  {
    id: 'help:offline',
    title: 'Using the app without internet',
    text: 'When the server cannot be reached the app shows saved events and details from the phone and says it is offline. It retries by itself and the notice disappears when the connection returns. Starting a help chat, publishing and AI helpers need a connection.'
  },
  {
    id: 'help:account',
    title: 'Account and sign out',
    text: 'Create an account on the sign-in screen with a name, email and password. Profile shows your account, interests and saved events. Tap Sign out and confirm to leave; saved items on the server stay with your account.'
  },
  {
    id: 'help:ai-helpers',
    title: 'AI helpers',
    text: 'AI helpers can summarize or translate an announcement, rewrite your help message for you to review, simplify an event description, and check your saved events for schedule clashes. They only use event data from the server, never invent facts, and nothing is sent or published until you do it yourself. When AI is not available these buttons are hidden.'
  }
]);
